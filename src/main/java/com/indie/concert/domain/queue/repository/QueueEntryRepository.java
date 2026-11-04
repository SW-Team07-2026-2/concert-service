package com.indie.concert.domain.queue.repository;

import com.indie.concert.domain.queue.entity.AdmittedMember;
import com.indie.concert.domain.queue.entity.QueueEntryEntity;
import com.indie.concert.domain.queue.entity.QueueMember;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 대기열 스냅샷 저장소 포트 — 구현은 JdbcQueueEntryRepository (배치 upsert).
 * 모든 upsert 는 seq 가 같거나 커질 때만 덮어씁니다 — 늦게 도착한 스냅샷이 입장 · 이탈 기록을 되돌리지 않습니다.
 */
public interface QueueEntryRepository {

    /** 5초 증분 스냅샷 — 재진입(더 큰 seq)이면 WAITING 으로 되살립니다 */
    void upsertWaiting(long concertId, List<QueueMember> members);

    /** 입장 토큰도 함께 — Redis 가 비워지면 findAdmittedSince 로 되살립니다 */
    void markAdmitted(long concertId, List<AdmittedMember> members, OffsetDateTime at);

    /** 이탈 — 하트비트 없음(입장 처리 때 건너뜀) · 대기 취소 */
    void markLeft(long concertId, List<QueueMember> members);

    /** seq 를 모르는 이탈 (대기열에서는 이미 빠졌고 입장 토큰만 있던 경우) */
    void markLeft(long concertId, long userId);

    /** 복구 — since 이후 입장해 토큰이 아직 살아 있을 사람 (이탈 · 재진입하면 status 가 바뀌어 빠집니다) */
    List<ActiveEntry> findAdmittedSince(long concertId, OffsetDateTime since);

    /** 복구 — WAITING 을 seq 순서로 (커버링 인덱스 idx_queue_rebuild) */
    List<QueueMember> findWaitingAfter(long concertId, long afterSeq, int limit);

    /** 지금까지 발급된 가장 큰 seq — 복구 후 INCR 이 과거 번호를 다시 쓰지 않게 */
    long maxSeq(long concertId);

    Optional<Position> findPosition(long concertId, long userId);

    /** degraded 순번 — 나보다 앞에 있는 WAITING 수 */
    long countWaitingBefore(long concertId, long seq);

    long countWaiting(long concertId);

    record Position(QueueEntryEntity.Status status, long seq) {}

    record ActiveEntry(long userId, String entryToken, OffsetDateTime admittedAt) {}
}
