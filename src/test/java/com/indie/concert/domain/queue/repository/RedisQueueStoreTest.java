package com.indie.concert.domain.queue.repository;

import com.indie.concert.domain.queue.entity.AdmittedMember;
import com.indie.concert.domain.queue.entity.QueueMember;
import com.indie.concert.global.config.ConcertProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 대기열 Lua 스크립트를 실제 Redis 에서 — 순서 · 중복 진입 · 이탈자 건너뛰기 · 리더 · 복구 후 seq. Docker 가 없으면 건너뜁니다 */
@Testcontainers(disabledWithoutDocker = true)
class RedisQueueStoreTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static RedisQueueStore store;

    private static final long CONCERT = 42;
    private static final Instant EXPIRE_AT = Instant.now().plusSeconds(3600);

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        ConcertProperties props = new ConcertProperties(null, null, null, null, null,
                new ConcertProperties.Queue(20, 1000, 200, 5000, 1000, 15, 10, 2000, 3000, 5000), null, null, null, null, null);
        store = new RedisQueueStore(redis, props);
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    @BeforeEach
    void flush() {
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void entersInOrderAndKeepsPlaceOnReentry() {
        assertThat(store.enter(CONCERT, 1, EXPIRE_AT).rank()).isZero();
        assertThat(store.enter(CONCERT, 2, EXPIRE_AT).rank()).isEqualTo(1);
        assertThat(store.enter(CONCERT, 3, EXPIRE_AT).rank()).isEqualTo(2);

        QueueStore.Entered again = store.enter(CONCERT, 2, EXPIRE_AT);
        assertThat(again.result()).isEqualTo(QueueStore.Entered.Result.ALREADY_WAITING);
        assertThat(again.rank()).isEqualTo(1);
        assertThat(again.total()).isEqualTo(3);
        assertThat(redis.getExpire("queue:concert:" + CONCERT)).isPositive();   // noeviction — 모든 키에 TTL
    }

    @Test
    void admissionSkipsUsersWithoutHeartbeat() {
        store.enter(CONCERT, 1, EXPIRE_AT);
        store.enter(CONCERT, 2, EXPIRE_AT);
        store.enter(CONCERT, 3, EXPIRE_AT);
        redis.delete("queue:alive:" + CONCERT + ":2");                              // 15초 동안 폴링 없음

        QueueStore.Admission a = store.admit(CONCERT, List.of("t-a", "t-b"), 10);

        assertThat(a.admitted()).extracting(AdmittedMember::userId).containsExactly(1L, 3L);
        assertThat(a.admitted()).extracting(AdmittedMember::entryToken).containsExactly("t-a", "t-b");   // DB 에 남길 토큰과 짝이 맞아야
        assertThat(a.dropped()).extracting(QueueMember::userId).containsExactly(2L);
        assertThat(store.entry(CONCERT, 1).orElseThrow().token()).isEqualTo("t-a");
        assertThat(store.entry(CONCERT, 3).orElseThrow().token()).isEqualTo("t-b");
        assertThat(store.entry(CONCERT, 2)).isEmpty();
        assertThat(store.enter(CONCERT, 1, EXPIRE_AT).result()).isEqualTo(QueueStore.Entered.Result.ALREADY_ADMITTED);
        assertThat(store.enter(CONCERT, 2, EXPIRE_AT).rank()).isZero();             // 다시 줄 서면 새 번호 (맨 뒤)
    }

    /** 입장 토큰 복구 — 남은 시간만큼 · 이미 있는 토큰은 덮지 않음 (SET NX) · 복구된 사람은 다시 줄 서지 않음 */
    @Test
    void restoredEntryTokensKeepRemainingTtlAndDoNotOverwrite() {
        store.enter(CONCERT, 1, EXPIRE_AT);
        store.admit(CONCERT, List.of("live"), 10);

        store.restoreEntries(CONCERT, List.of(new QueueStore.EntryGrant(1, "old", 60_000),
                new QueueStore.EntryGrant(2, "restored", 60_000)));

        assertThat(store.entry(CONCERT, 1).orElseThrow().token()).isEqualTo("live");
        QueueStore.Entry restored = store.entry(CONCERT, 2).orElseThrow();
        assertThat(restored.token()).isEqualTo("restored");
        assertThat(restored.ttlSeconds()).isBetween(55L, 60L);
        assertThat(store.enter(CONCERT, 2, EXPIRE_AT).result()).isEqualTo(QueueStore.Entered.Result.ALREADY_ADMITTED);
    }

    @Test
    void admissionStopsAtTokenCount() {
        for (long u = 1; u <= 5; u++) store.enter(CONCERT, u, EXPIRE_AT);
        assertThat(store.admit(CONCERT, List.of("a", "b"), 10).admitted()).hasSize(2);
        assertThat(store.size(CONCERT)).isEqualTo(3);
    }

    @Test
    void onlyOneAdmitterLeads() {
        assertThat(store.lead(CONCERT, "pod-a")).isTrue();
        assertThat(store.lead(CONCERT, "pod-b")).isFalse();
        assertThat(store.lead(CONCERT, "pod-a")).isTrue();                          // 리더는 연장
    }

    /** 복구 후 seq 를 DB 최댓값 이상으로 — 안 하면 새 진입자가 기존 대기자 앞에 섭니다 */
    @Test
    void rebuildRestoresOrderAndKeepsSequenceAhead() {
        assertThat(store.isReady(CONCERT)).isFalse();
        store.restore(CONCERT, List.of(new QueueMember(10, 5), new QueueMember(11, 9)), EXPIRE_AT);
        store.markReady(CONCERT, 12, EXPIRE_AT);

        assertThat(store.isReady(CONCERT)).isTrue();
        assertThat(store.enter(CONCERT, 12, EXPIRE_AT).rank()).isEqualTo(2);
        assertThat(store.waitingAfter(CONCERT, 0, 10)).containsExactly(
                new QueueMember(10, 5), new QueueMember(11, 9), new QueueMember(12, 13));
        assertThat(store.lastSnapshotSeq(CONCERT)).hasValue(12);
    }

    @Test
    void leaveReturnsSeqAndDropsEntry() {
        store.enter(CONCERT, 1, EXPIRE_AT);
        store.enter(CONCERT, 2, EXPIRE_AT);
        assertThat(store.leave(CONCERT, 2)).hasValue(2);
        assertThat(store.rank(CONCERT, 2)).isEmpty();
        assertThat(store.leave(CONCERT, 2)).isEmpty();
    }

    @Test
    void rebuildLockIsExclusive() {
        String token = store.lockRebuild(CONCERT);
        assertThat(token).isNotNull();
        assertThat(store.lockRebuild(CONCERT)).isNull();
        store.unlockRebuild(CONCERT, token);
        assertThat(store.lockRebuild(CONCERT)).isNotNull();
    }
}
