package com.indie.concert.domain.queue.repository;

import com.indie.concert.domain.queue.entity.AdmittedMember;
import com.indie.concert.domain.queue.entity.QueueMember;
import com.indie.concert.global.config.ConcertProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 대기열 Redis (16 §1 · 08 §8.2). 키 — 모두 TTL 이 있습니다 (noeviction 은 메모리가 차면 쓰기를 거부하므로, 16 §4)
 * <pre>
 * queue:concert:{id}        ZSET  member=userId · score=seq        공연 시작 + 1일
 * queue:seq:{id}            INCR  단조 증가 순번                    〃
 * queue:ready:{id}          센티넬 — 없으면 재구성                   〃
 * queue:snapshot:{id}       DB 에 반영한 마지막 seq                  〃
 * queue:alive:{id}:{uid}    생존 신호                               15초
 * entry:{id}:{uid}          입장 토큰 (DB 에도 · 복구 때 남은 시간만큼) 10분
 * queue:admitter:{id}       입장 처리기 리더                         5초 (리더가 연장)
 * queue:snapshotter:{id}    스냅샷 차례                              4초
 * queue:rebuild:{id}        재구성 락                                60초
 * </pre>
 * Lua 는 resources/redis/*.lua (62B).
 * 🔴 입장 처리 · 토큰 복구 스크립트가 KEYS 밖의 키(alive · entry)를 만집니다 — 단일 인스턴스 전제. 클러스터로 가면 해시태그가 필요합니다.
 */
@Component
public class RedisQueueStore implements QueueStore {

    private static final RedisScript<List> ENTER =
            RedisScript.of(new ClassPathResource("redis/queue-enter.lua"), List.class);

    /** 반환 — [uid, seq, 1(입장)|0(이탈), uid, seq, …] */
    private static final RedisScript<List> ADMIT =
            RedisScript.of(new ClassPathResource("redis/queue-admit.lua"), List.class);

    /** ARGV — concertId, (uid, token, ttlMs) × N */
    private static final RedisScript<Long> RESTORE_ENTRIES =
            RedisScript.of(new ClassPathResource("redis/queue-restore-entries.lua"), Long.class);

    private static final RedisScript<Long> LEAD =
            RedisScript.of(new ClassPathResource("redis/queue-lead.lua"), Long.class);

    private static final RedisScript<String> LEAVE =
            RedisScript.of(new ClassPathResource("redis/queue-leave.lua"), String.class);

    private static final RedisScript<Long> UNLOCK =
            RedisScript.of(new ClassPathResource("redis/unlock-if-owner.lua"), Long.class);

    private static final RedisScript<Long> MARK_READY =
            RedisScript.of(new ClassPathResource("redis/queue-mark-ready.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final long aliveTtlSeconds;
    private final long entryTtlSeconds;
    private final long leaderTtlMs;

    public RedisQueueStore(@Qualifier("queueRedis") StringRedisTemplate redis, ConcertProperties props) {
        this.redis = redis;
        this.aliveTtlSeconds = props.queue().aliveTtlSeconds();
        this.entryTtlSeconds = Duration.ofMinutes(props.queue().entryTtlMinutes()).toSeconds();
        this.leaderTtlMs = props.queue().leaderTtlMs();
    }

    @Override
    public boolean isReady(long concertId) {
        return call(() -> Boolean.TRUE.equals(redis.hasKey(readyKey(concertId))));
    }

    @Override
    public Entered enter(long concertId, long userId, Instant expireAt) {
        List<?> r = call(() -> redis.execute(ENTER,
                List.of(queueKey(concertId), seqKey(concertId), aliveKey(concertId, userId), entryKey(concertId, userId)),
                String.valueOf(userId), String.valueOf(expireAt.getEpochSecond()), String.valueOf(aliveTtlSeconds)));
        Entered.Result result = switch (((Number) r.get(0)).intValue()) {
            case 0 -> Entered.Result.ENTERED;
            case 1 -> Entered.Result.ALREADY_WAITING;
            default -> Entered.Result.ALREADY_ADMITTED;
        };
        return new Entered(result, ((Number) r.get(1)).longValue(), ((Number) r.get(2)).longValue());
    }

    @Override
    public OptionalLong rank(long concertId, long userId) {
        Long rank = call(() -> redis.opsForZSet().rank(queueKey(concertId), String.valueOf(userId)));
        return rank == null ? OptionalLong.empty() : OptionalLong.of(rank);
    }

    @Override
    public long size(long concertId) {
        Long size = call(() -> redis.opsForZSet().zCard(queueKey(concertId)));
        return size == null ? 0 : size;
    }

    @Override
    public void heartbeat(long concertId, long userId) {
        call(() -> {
            redis.opsForValue().set(aliveKey(concertId, userId), "1", aliveTtlSeconds, TimeUnit.SECONDS);
            return null;
        });
    }

    @Override
    public Optional<Entry> entry(long concertId, long userId) {
        String key = entryKey(concertId, userId);
        String token = call(() -> redis.opsForValue().get(key));
        if (token == null) return Optional.empty();
        Long ttl = call(() -> redis.getExpire(key, TimeUnit.SECONDS));
        return Optional.of(new Entry(token, ttl == null || ttl < 0 ? 0 : ttl));
    }

    @Override
    public OptionalLong leave(long concertId, long userId) {
        String score = call(() -> redis.execute(LEAVE,
                List.of(queueKey(concertId), aliveKey(concertId, userId), entryKey(concertId, userId)), String.valueOf(userId)));
        return score == null ? OptionalLong.empty() : OptionalLong.of((long) Double.parseDouble(score));
    }

    @Override
    public boolean lead(long concertId, String owner) {
        Long led = call(() -> redis.execute(LEAD, List.of("queue:admitter:" + concertId), owner, String.valueOf(leaderTtlMs)));
        return led != null && led == 1;
    }

    @Override
    public Admission admit(long concertId, List<String> tokens, int maxPops) {
        List<String> args = new ArrayList<>(List.of(String.valueOf(concertId), String.valueOf(maxPops),
                String.valueOf(entryTtlSeconds)));
        args.addAll(tokens);
        List<?> out = call(() -> redis.execute(ADMIT, List.of(queueKey(concertId)), args.toArray()));
        List<AdmittedMember> admitted = new ArrayList<>();
        List<QueueMember> dropped = new ArrayList<>();
        for (int i = 0; i + 2 < out.size(); i += 3) {
            long userId = Long.parseLong(out.get(i).toString());
            long seq = (long) Double.parseDouble(out.get(i + 1).toString());
            // 스크립트는 입장시킨 순서대로 tokens 를 앞에서부터 줍니다 (ARGV[3 + given])
            if ("1".equals(out.get(i + 2).toString())) admitted.add(new AdmittedMember(userId, seq, tokens.get(admitted.size())));
            else dropped.add(new QueueMember(userId, seq));
        }
        return new Admission(admitted, dropped);
    }

    @Override
    public boolean trySnapshotTurn(long concertId) {
        return call(() -> Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent("queue:snapshotter:" + concertId, "1", Duration.ofSeconds(4))));
    }

    @Override
    public OptionalLong lastSnapshotSeq(long concertId) {
        String value = call(() -> redis.opsForValue().get(snapshotKey(concertId)));
        return value == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(value));
    }

    @Override
    public void saveSnapshotSeq(long concertId, long seq, Instant expireAt) {
        call(() -> {
            redis.opsForValue().set(snapshotKey(concertId), String.valueOf(seq));
            redis.expireAt(snapshotKey(concertId), Date.from(expireAt));
            return null;
        });
    }

    @Override
    public List<QueueMember> waitingAfter(long concertId, long afterSeq, int limit) {
        Set<ZSetOperations.TypedTuple<String>> rows = call(() -> redis.opsForZSet()
                .rangeByScoreWithScores(queueKey(concertId), afterSeq + 1, Double.POSITIVE_INFINITY, 0, limit));
        if (rows == null) return List.of();
        return rows.stream().map(t -> new QueueMember(Long.parseLong(t.getValue()), t.getScore().longValue())).toList();
    }

    @Override
    public String lockRebuild(long concertId) {
        String token = UUID.randomUUID().toString();
        Boolean locked = call(() -> redis.opsForValue().setIfAbsent("queue:rebuild:" + concertId, token, Duration.ofSeconds(60)));
        return Boolean.TRUE.equals(locked) ? token : null;
    }

    @Override
    public void unlockRebuild(long concertId, String token) {
        call(() -> redis.execute(UNLOCK, List.of("queue:rebuild:" + concertId), token));
    }

    @Override
    public void restore(long concertId, List<QueueMember> members, Instant expireAt) {
        if (members.isEmpty()) return;
        Set<ZSetOperations.TypedTuple<String>> tuples = members.stream()
                .map(m -> ZSetOperations.TypedTuple.of(String.valueOf(m.userId()), (double) m.seq()))
                .collect(Collectors.toSet());
        call(() -> redis.opsForZSet().addIfAbsent(queueKey(concertId), tuples));   // ZADD NX — 재구성 중 새로 들어온 순번을 덮지 않음
    }

    @Override
    public void restoreEntries(long concertId, List<EntryGrant> grants) {
        if (grants.isEmpty()) return;
        List<String> args = new ArrayList<>(List.of(String.valueOf(concertId)));
        for (EntryGrant g : grants) {
            args.add(String.valueOf(g.userId()));
            args.add(g.token());
            args.add(String.valueOf(g.ttlMs()));
        }
        call(() -> redis.execute(RESTORE_ENTRIES, List.of(), args.toArray()));
    }

    @Override
    public void markReady(long concertId, long maxSeq, Instant expireAt) {
        call(() -> redis.execute(MARK_READY, List.of(seqKey(concertId), snapshotKey(concertId), readyKey(concertId), queueKey(concertId)),
                String.valueOf(maxSeq), String.valueOf(expireAt.getEpochSecond())));
    }

    private static <T> T call(Supplier<T> op) {
        try {
            return op.get();
        } catch (DataAccessException e) {
            throw new Unavailable(e);
        }
    }

    private static String queueKey(long concertId) { return "queue:concert:" + concertId; }
    private static String seqKey(long concertId) { return "queue:seq:" + concertId; }
    private static String readyKey(long concertId) { return "queue:ready:" + concertId; }
    private static String snapshotKey(long concertId) { return "queue:snapshot:" + concertId; }
    private static String aliveKey(long concertId, long userId) { return "queue:alive:" + concertId + ":" + userId; }
    private static String entryKey(long concertId, long userId) { return "entry:" + concertId + ":" + userId; }
}
