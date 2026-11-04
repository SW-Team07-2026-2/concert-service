package com.indie.concert.domain.queue.repository;

import com.indie.concert.domain.queue.entity.AdmittedMember;
import com.indie.concert.domain.queue.entity.QueueMember;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 대기열 실시간 저장소 포트 — 구현은 RedisQueueStore (대기열 Redis · noeviction).
 * 진입 · 조회는 이 포트만 거칩니다 — 응답 경로에 DB 가 없습니다 (16 §0).
 * 🔴 모든 메서드는 Redis 장애를 Unavailable 로 바꿔 던집니다 — 호출자가 DB 폴백(degraded) · 503 을 고릅니다.
 */
public interface QueueStore {

    /** 센티넬 queue:ready:{id} — 없으면 Redis 가 비워진 것 (ZCARD 0 으로는 "아무도 없음"과 구분이 안 됨) */
    boolean isReady(long concertId);

    /** 진입 — INCR seq + ZADD 를 한 번에. 이미 대기 중이면 순번 유지, 입장 토큰이 있으면 ALREADY_ADMITTED */
    Entered enter(long concertId, long userId, Instant expireAt);

    /** 0 부터 */
    OptionalLong rank(long concertId, long userId);

    long size(long concertId);

    /** 생존 신호 — 15초 동안 없으면 입장 처리 때 건너뜁니다 (FR-CON-24) */
    void heartbeat(long concertId, long userId);

    Optional<Entry> entry(long concertId, long userId);

    /** 대기 취소 — 대기열 · 생존 신호 · 입장 토큰을 지웁니다. 대기 중이었으면 그 seq */
    OptionalLong leave(long concertId, long userId);

    /** 입장 처리기 리더 — 이미 내 것이면 연장. replica 가 여럿이어도 공연당 하나만 입장시킵니다 (FR-CON-25) */
    boolean lead(long concertId, String owner);

    /**
     * 앞에서부터 꺼내 살아 있는 사람에게 입장 토큰을 줍니다 (tokens 수만큼). 생존 신호가 없는 사람은 버립니다.
     * 이탈자가 앞에 몰려 있어도 maxPops 까지만 꺼냅니다.
     */
    Admission admit(long concertId, List<String> tokens, int maxPops);

    /** 스냅샷 차례 — 5초 동안 한 replica 만 */
    boolean trySnapshotTurn(long concertId);

    OptionalLong lastSnapshotSeq(long concertId);

    void saveSnapshotSeq(long concertId, long seq, Instant expireAt);

    /** seq 가 afterSeq 보다 큰 대기자 (seq 순) */
    List<QueueMember> waitingAfter(long concertId, long afterSeq, int limit);

    /** 재구성 락 — null 이면 다른 replica 가 재구성 중 */
    String lockRebuild(long concertId);

    void unlockRebuild(long concertId, String token);

    /** DB → ZADD (이미 있는 사람은 그대로) */
    void restore(long concertId, List<QueueMember> members, Instant expireAt);

    /** DB → 입장 토큰 (SET NX — 이미 있는 토큰은 그대로). ttlMs 는 남은 시간 */
    void restoreEntries(long concertId, List<EntryGrant> grants);

    /** 재구성 완료 — seq 를 maxSeq 이상으로 맞추고 센티넬을 세웁니다 */
    void markReady(long concertId, long maxSeq, Instant expireAt);

    record Entered(Result result, long rank, long total) {
        public enum Result { ENTERED, ALREADY_WAITING, ALREADY_ADMITTED }
    }

    record Entry(String token, long ttlSeconds) {}

    record EntryGrant(long userId, String token, long ttlMs) {}

    record Admission(List<AdmittedMember> admitted, List<QueueMember> dropped) {}

    class Unavailable extends RuntimeException {
        public Unavailable(Throwable cause) {
            super("queue redis unavailable: " + cause.getMessage(), cause);
        }
    }
}
