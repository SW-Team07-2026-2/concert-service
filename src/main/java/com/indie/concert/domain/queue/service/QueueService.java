package com.indie.concert.domain.queue.service;

import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.queue.dto.response.QueueMetrics;
import com.indie.concert.domain.queue.dto.response.QueueRebuilt;
import com.indie.concert.domain.queue.dto.response.QueueStatus;
import com.indie.concert.domain.queue.entity.QueueMember;
import com.indie.concert.domain.queue.exception.QueueErrorCode;
import com.indie.concert.domain.queue.repository.QueueEntryRepository;
import com.indie.concert.domain.queue.repository.QueueStore;
import com.indie.concert.domain.seat.repository.ConcertSeatRepository;
import com.indie.concert.global.common.exception.CommonErrorCode;
import com.indie.concert.global.common.exception.GeneralException;
import com.indie.concert.global.config.ConcertProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 대기열 (N4 ~ N6 · i1 · i2 · 16_대기열_Redis_운영). 원칙 — "Redis 를 안 죽게"보다 "죽어도 복구되게".
 * <pre>
 * 진입 · 조회   Redis 만             응답 경로에 DB 없음 — ✏️ 11/25 공연 판매 값 · 남은 좌석 수는 Pod 메모리 3초 (몰릴 때 DB 는 3초에 1번)
 * 입장 처리     1초 · 리더 하나        ZPOPMIN N → 입장 토큰 7분 → DB 즉시 반영
 * 스냅샷        5초 · 증분 배치        Redis → queue_entry
 * 복구          센티넬이 없으면        queue_entry → ZADD (순서 그대로) + 입장 토큰 (남은 시간만큼) · 다른 replica 가 복구 중이면 DB COUNT 로 degraded
 * </pre>
 * 입장 토큰 검사(requireEntry · entryLeft)도 여기 있습니다 — 좌석 선점과 좌석 화면 타이머가 부릅니다. 결제(N16)는 선점만 봅니다 (Gateway 는 관여하지 않음).
 */
@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final QueueStore store;
    private final QueueEntryRepository entries;
    private final ConcertRepository concertRepository;
    private final ConcertSeatRepository concertSeatRepository;
    private final ConcertProperties.Queue props;
    private final Timer rebuildTimer;
    private final Clock clock;
    private final String instanceId = UUID.randomUUID().toString();

    /**
     * ✏️ 11/25 k6 3,000명 — 2초 폴링마다 공연을 DB 에서 읽어 초당 약 1,500번 · DB 풀 대기 155번.
     * 판매 값 · 남은 좌석 수는 Pod 메모리에 3초 둡니다 (캐시가 비면 한 요청만 DB 로 · 나머지는 그 결과를 기다림). 판매 상태는 요청 시각으로 다시 계산
     */
    private static final Duration SNAPSHOT_TTL = Duration.ofSeconds(3);
    private final Cache<Long, Sale> sales = Caffeine.newBuilder().expireAfterWrite(SNAPSHOT_TTL).maximumSize(1_000).build();
    private final Cache<Long, Seats> seats = Caffeine.newBuilder().expireAfterWrite(SNAPSHOT_TTL).maximumSize(1_000).build();

    /** 공연에서 대기열이 쓰는 값만 — JPA 엔티티를 스레드 사이에 공유하지 않게 */
    private record Sale(boolean queueEnabled, OffsetDateTime startsAt, OffsetDateTime bookingOpenAt) {
        static Sale of(ConcertEntity c) {
            return new Sale(c.isQueueEnabled(), c.getStartsAt(), c.getBookingOpenAt());
        }

        ConcertEntity.SaleStatus status(OffsetDateTime now) {
            return ConcertEntity.saleStatus(startsAt, bookingOpenAt, now);
        }
    }

    /** 좌석을 아직 안 올린 공연(total 0)은 매진 · 입장 멈춤으로 보지 않습니다 — 예전처럼 들어갑니다 */
    private record Seats(long total, long unsold, long available) {
        boolean soldOut() { return total > 0 && unsold == 0; }
        boolean noneToPick() { return total > 0 && available == 0; }
    }

    public QueueService(QueueStore store, QueueEntryRepository entries, ConcertRepository concertRepository,
                        ConcertSeatRepository concertSeatRepository, ConcertProperties props, MeterRegistry meterRegistry,
                        Clock clock) {
        this.store = store;
        this.entries = entries;
        this.concertRepository = concertRepository;
        this.concertSeatRepository = concertSeatRepository;
        this.props = props.queue();
        this.rebuildTimer = Timer.builder("queue.rebuild").description("대기열 재구성 소요 (16 §8)").register(meterRegistry);
        this.clock = clock;
    }

    /** N4 — 진입. 이미 대기 중이면 409 + 지금 순번 (순번은 그대로 유지) · ✏️ 11/25 매진이면 410 SOLD_OUT (줄을 세우지 않음) */
    public QueueStatus enter(long concertId, long userId) {
        Sale sale = queueSale(concertId);
        switch (sale.status(now())) {
            case SCHEDULED -> throw new GeneralException(QueueErrorCode.QUEUE_NOT_OPEN);
            case CLOSED -> throw closed();
            case ON_SALE -> { }
        }
        if (seats(concertId).soldOut()) throw new GeneralException(QueueErrorCode.SOLD_OUT);
        QueueStore.Entered entered;
        try {
            if (!store.isReady(concertId) && !rebuild(concertId, sale.startsAt(), false)) throw unavailable();
            entered = store.enter(concertId, userId, expireAt(sale.startsAt()));
        } catch (QueueStore.Unavailable e) {
            log.warn("queue_enter_unavailable concertId={} error={}", concertId, e.getMessage());
            throw unavailable();
        }
        return switch (entered.result()) {
            case ENTERED -> waiting(concertId, entered.rank() + 1, entered.total());
            case ALREADY_WAITING -> throw new GeneralException(QueueErrorCode.ALREADY_IN_QUEUE)
                    .with("rank", entered.rank() + 1);
            case ALREADY_ADMITTED -> throw new GeneralException(QueueErrorCode.ALREADY_IN_QUEUE, "이미 입장했습니다 — 순번 조회로 입장 토큰을 받으세요")
                    .with("rank", 0);
        };
    }

    /**
     * N5 — 순번 조회 (pollAfterMs 간격 · ✏️ 11/25 순번이 멀수록 2 → 15초) + 생존 신호. 🔴 복구 중에도 503 을 내지 않습니다 — DB 로 순번을 계산해 degraded 로 내려줍니다.
     * 프론트는 degraded 면 pollAfterMs 대로 다시 부르기만 하면 됩니다 (15 §4). ✏️ 11/25 기다리는 중에 매진되면 SOLD_OUT — 조회를 멈춥니다
     */
    public QueueStatus status(long concertId, long userId) {
        Sale sale = queueSale(concertId);
        try {
            if (!store.isReady(concertId) && !rebuild(concertId, sale.startsAt(), false)) return degraded(concertId, userId);
            OptionalLong rank = store.rank(concertId, userId);
            if (rank.isPresent()) {
                if (seats(concertId).soldOut()) return SOLD_OUT;
                store.heartbeat(concertId, userId);
                return waiting(concertId, rank.getAsLong() + 1, store.size(concertId));
            }
            Optional<QueueStore.Entry> entry = store.entry(concertId, userId);
            if (entry.isPresent()) {
                return new QueueStatus("ADMITTED", 0L, null, null, null, null, entry.get().token(), entry.get().ttlSeconds());
            }
        } catch (QueueStore.Unavailable e) {
            log.warn("queue_status_degraded concertId={} error={}", concertId, e.getMessage());
            return degraded(concertId, userId);
        }
        // 대기열에도 입장 토큰에도 없음 — 기록이 있으면 이탈 · 토큰 만료 (다시 진입하면 맨 뒤)
        return entries.findPosition(concertId, userId).map(p -> expired()).orElseThrow(QueueService::notInQueue);
    }

    /** N6 — 대기 취소 (입장 토큰도 반납). 이탈은 빈도가 낮아 배치 없이 바로 DB 에 남깁니다 */
    public void leave(long concertId, long userId) {
        concertRepository.findById(concertId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        OptionalLong seq;
        try {
            seq = store.leave(concertId, userId);
        } catch (QueueStore.Unavailable e) {
            throw unavailable();
        }
        if (seq.isPresent()) entries.markLeft(concertId, List.of(new QueueMember(userId, seq.getAsLong())));
        else entries.markLeft(concertId, userId);
    }

    /**
     * 좌석 선점(N10) 앞단 (FR-CON-21) — 대기열을 켠 공연은 입장 토큰이 맞아야 합니다.
     * 끈 공연은 같은 판매 시각만 검사합니다 (FR-CON-28). 🔴 Redis 를 못 읽으면 통과시키지 않습니다 — 대기열 우회가 됩니다.
     * @return 입장 토큰 남은 초 — 선점 마감이 이어받습니다 (입장부터 결제하기까지 시계 하나). 대기열을 끈 공연은 토큰이 없어 empty
     */
    public OptionalLong requireEntry(ConcertEntity concert, long userId, String entryToken) {
        ConcertEntity.SaleStatus sale = concert.saleStatus(now());
        if (sale == ConcertEntity.SaleStatus.CLOSED) throw closed();
        if (!concert.isQueueEnabled()) {
            if (sale == ConcertEntity.SaleStatus.SCHEDULED) {
                throw new GeneralException(QueueErrorCode.QUEUE_NOT_OPEN);
            }
            return OptionalLong.empty();
        }
        if (entryToken == null || entryToken.isBlank()) throw entryRequired();
        return OptionalLong.of(entryLeft(concert.getId(), userId, entryToken));
    }

    /**
     * 입장 토큰 남은 초 (Redis TTL) — 좌석 화면 타이머 · 좌석 선점이 씁니다. DB 를 거치지 않습니다.
     * 입장 기록이 없거나 만료면 QUEUE_NOT_ADMITTED · 토큰이 다르면 ENTRY_TOKEN_INVALID
     */
    public long entryLeft(long concertId, long userId, String entryToken) {
        Optional<QueueStore.Entry> entry;
        try {
            entry = store.entry(concertId, userId);
        } catch (QueueStore.Unavailable e) {
            throw unavailable();
        }
        if (entry.isEmpty()) throw new GeneralException(QueueErrorCode.QUEUE_NOT_ADMITTED);
        if (!MessageDigest.isEqual(entry.get().token().getBytes(StandardCharsets.UTF_8),
                entryToken.strip().getBytes(StandardCharsets.UTF_8))) {
            throw new GeneralException(QueueErrorCode.ENTRY_TOKEN_INVALID);
        }
        return entry.get().ttlSeconds();
    }

    /**
     * 입장 처리 (1초) — 리더만. 센티넬이 없으면 여기서도 복구합니다 (아무도 폴링하지 않아도 복구되게).
     * ✏️ 11/25 지금 고를 좌석이 없으면(전부 선점 · 결제 중 · 판매) 입장을 멈춥니다 — 들여보내도 빈 좌석 화면에서 7분 시계만 흐릅니다.
     * 선점이 풀리면 줄 순서대로 다시 들어갑니다
     */
    public void admit(ConcertEntity concert) {
        long concertId = concert.getId();
        try {
            if (!store.lead(concertId, instanceId)) return;
            if (!store.isReady(concertId) && !rebuild(concertId, concert.getStartsAt(), false)) return;
            if (seats(concertId).noneToPick()) return;
            List<String> tokens = new ArrayList<>(props.admitBatch());
            for (int i = 0; i < props.admitBatch(); i++) tokens.add(newToken());
            QueueStore.Admission admission = store.admit(concertId, tokens, props.maxPopsPerTick());
            entries.markAdmitted(concertId, admission.admitted(), now());
            entries.markLeft(concertId, admission.dropped());
            if (!admission.admitted().isEmpty() || !admission.dropped().isEmpty()) {
                log.info("queue_admitted concertId={} admitted={} dropped={}", concertId,
                        admission.admitted().size(), admission.dropped().size());
            }
        } catch (QueueStore.Unavailable e) {
            log.warn("queue_admit_skipped concertId={} error={}", concertId, e.getMessage());
        }
    }

    /** 스냅샷 (5초 · 증분) — 마지막으로 반영한 seq 이후만 배치 upsert */
    public void snapshot(ConcertEntity concert) {
        long concertId = concert.getId();
        try {
            if (!store.trySnapshotTurn(concertId) || !store.isReady(concertId)) return;
            long last = store.lastSnapshotSeq(concertId).orElseGet(() -> entries.maxSeq(concertId));
            while (true) {
                List<QueueMember> batch = store.waitingAfter(concertId, last, props.snapshotBatch());
                if (batch.isEmpty()) return;
                entries.upsertWaiting(concertId, batch);
                last = batch.get(batch.size() - 1).seq();
                store.saveSnapshotSeq(concertId, last, expireAt(concert.getStartsAt()));
                if (batch.size() < props.snapshotBatch()) return;
            }
        } catch (QueueStore.Unavailable e) {
            log.warn("queue_snapshot_skipped concertId={} error={}", concertId, e.getMessage());
        }
    }

    /** i1 — 수동 재구성. 센티넬이 있어도 DB 의 대기자를 다시 채웁니다 (이미 있는 순번은 그대로) */
    public QueueRebuilt rebuildManually(long concertId) {
        ConcertEntity concert = concertRepository.findById(concertId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        long started = System.nanoTime();
        boolean rebuilt;
        try {
            rebuilt = rebuild(concert.getId(), concert.getStartsAt(), true);
        } catch (QueueStore.Unavailable e) {
            throw unavailable();
        }
        return new QueueRebuilt(rebuilt, (System.nanoTime() - started) / 1_000_000);
    }

    /** i2 — ZCARD · 마지막 스냅샷 seq · 센티넬 상태 */
    public QueueMetrics metrics(long concertId) {
        concertRepository.findById(concertId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        try {
            return new QueueMetrics(concertId, store.isReady(concertId), store.size(concertId),
                    store.lastSnapshotSeq(concertId).orElse(0), entries.countWaiting(concertId));
        } catch (QueueStore.Unavailable e) {
            throw unavailable();
        }
    }

    /** 입장 처리 · 스냅샷 대상 — 대기열을 켠 판매 중 공연 */
    public List<ConcertEntity> activeConcerts() {
        return concertRepository.findQueueActive(now());
    }

    /**
     * DB → Redis (16 §2). true = 이제 준비됨 · false = 다른 replica 가 재구성 중.
     * seq 도 DB 의 최댓값 이상으로 되돌립니다 — 안 하면 복구 직후 진입자가 기존 대기자보다 앞 번호를 받습니다.
     * 입장 토큰도 되살립니다 (10/24) — 안 하면 좌석을 고르던 사람(최대 초당 입장 수 × 7분)이 전부 다시 줄을 섭니다.
     */
    private boolean rebuild(long concertId, OffsetDateTime startsAt, boolean force) {
        String lock = store.lockRebuild(concertId);
        if (lock == null) return false;
        long started = System.nanoTime();
        try {
            if (!force && store.isReady(concertId)) return true;   // 이중 확인
            long last = 0;
            int restored = 0;
            while (true) {
                List<QueueMember> batch = entries.findWaitingAfter(concertId, last, props.snapshotBatch());
                if (batch.isEmpty()) break;
                store.restore(concertId, batch, expireAt(startsAt));
                restored += batch.size();
                last = batch.get(batch.size() - 1).seq();
            }
            int tokens = restoreEntryTokens(concertId);
            store.markReady(concertId, entries.maxSeq(concertId), expireAt(startsAt));
            log.info("queue_rebuilt concertId={} restored={} entryTokens={} force={}", concertId, restored, tokens, force);
            return true;
        } finally {
            rebuildTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            store.unlockRebuild(concertId, lock);
        }
    }

    /** 입장 토큰 — 남은 시간만큼만. 건수는 초당 입장 수 × 7분으로 묶여 있어(기본 8,400) 한 번에 읽습니다 */
    private int restoreEntryTokens(long concertId) {
        Duration ttl = Duration.ofMinutes(props.entryTtlMinutes());
        Instant now = clock.instant();
        List<QueueStore.EntryGrant> grants = entries.findAdmittedSince(concertId, now().minus(ttl)).stream()
                .map(e -> new QueueStore.EntryGrant(e.userId(), e.entryToken(),
                        Duration.between(now, e.admittedAt().toInstant().plus(ttl)).toMillis()))
                .filter(g -> g.ttlMs() > 0)
                .toList();
        for (int i = 0; i < grants.size(); i += props.snapshotBatch()) {
            store.restoreEntries(concertId, grants.subList(i, Math.min(i + props.snapshotBatch(), grants.size())));
        }
        return grants.size();
    }

    private QueueStatus degraded(long concertId, long userId) {
        QueueEntryRepository.Position p = entries.findPosition(concertId, userId).orElseThrow(QueueService::notInQueue);
        return switch (p.status()) {
            case WAITING -> {
                long rank = entries.countWaitingBefore(concertId, p.seq()) + 1;
                yield new QueueStatus("WAITING", rank, entries.countWaiting(concertId), eta(rank),
                        props.degradedPollAfterMs(), true, null, null);
            }
            // 토큰은 Redis 에서 내줍니다 — 복구되면(DB 에서 되살림) 다음 폴링에서 같은 토큰이 나갑니다
            case ADMITTED -> new QueueStatus("ADMITTED", 0L, null, null, props.degradedPollAfterMs(), true, null, null);
            case LEFT -> expired();
        };
    }

    private Sale queueSale(long concertId) {
        Sale sale = sales.get(concertId, id -> concertRepository.findById(id).map(Sale::of).orElse(null));   // 없으면 캐시하지 않음
        if (sale == null) throw new GeneralException(CommonErrorCode.NOT_FOUND);
        if (!sale.queueEnabled()) {
            throw new GeneralException(QueueErrorCode.QUEUE_DISABLED);
        }
        return sale;
    }

    private Seats seats(long concertId) {
        return seats.get(concertId, id -> {
            ConcertSeatRepository.SeatCounts c = concertSeatRepository.countSeats(id);
            return new Seats(c.getTotal(), c.getUnsold(), c.getAvailable());
        });
    }

    /** 고를 좌석이 없어 입장이 멈춘 동안은 가장 긴 간격으로 — 순번이 움직이지 않습니다 */
    private QueueStatus waiting(long concertId, long rank, long total) {
        long eta = eta(rank);
        long poll = seats(concertId).noneToPick() ? props.pollMaxMs() : pollAfter(eta);
        return new QueueStatus("WAITING", rank, total, eta, poll, null, null, null);
    }

    /** ✏️ 11/25 순번이 멀수록 천천히 — 예상 대기의 1/3 (입장 전에 3번은 다시 묻게) · poll-after-ms ~ poll-max-ms */
    private long pollAfter(long etaSeconds) {
        return Math.max(props.pollAfterMs(), Math.min(props.pollMaxMs(), etaSeconds * 1000 / 3));
    }

    private static final QueueStatus SOLD_OUT = new QueueStatus("SOLD_OUT", null, null, null, null, null, null, null);

    private static QueueStatus expired() {
        return new QueueStatus("EXPIRED", null, null, null, null, null, null, null);
    }

    /** 1초에 admitBatch 명씩 입장 — 이탈자가 빠지면 실제로는 더 빠릅니다 */
    private long eta(long rank) {
        return (long) Math.ceil(rank * (double) props.admitIntervalMs() / (props.admitBatch() * 1000.0));
    }

    private static Instant expireAt(OffsetDateTime startsAt) {
        return startsAt.plusDays(1).toInstant();   // 16 §4 — 공연 종료 + 1일
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static GeneralException closed() {
        return new GeneralException(QueueErrorCode.QUEUE_CLOSED);
    }

    private static GeneralException entryRequired() {
        return new GeneralException(QueueErrorCode.ENTRY_TOKEN_REQUIRED);
    }

    private static GeneralException notInQueue() {
        return new GeneralException(QueueErrorCode.NOT_IN_QUEUE);
    }

    private GeneralException unavailable() {
        return new GeneralException(QueueErrorCode.QUEUE_UNAVAILABLE)
                .with("retryAfterMs", props.degradedPollAfterMs());
    }
}
