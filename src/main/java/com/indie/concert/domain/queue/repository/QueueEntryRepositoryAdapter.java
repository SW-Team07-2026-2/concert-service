package com.indie.concert.domain.queue.repository;

import com.indie.concert.domain.queue.entity.AdmittedMember;
import com.indie.concert.domain.queue.entity.QueueEntryEntity;
import com.indie.concert.domain.queue.entity.QueueMember;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * queue_entry 배치 upsert — 1만 명이 들어와도 DB 에는 5초마다 한 문장 (16 §1).
 * 개별 INSERT 1만 번은 커밋 · 왕복이 1만 번이라 불가능합니다.
 */
@Repository
public class QueueEntryRepositoryAdapter implements QueueEntryRepository {

    private final QueueEntryJpaRepository jpa;

    public QueueEntryRepositoryAdapter(QueueEntryJpaRepository jpa) {
        this.jpa = jpa;
    }

    /** 같은 seq 는 덮어쓰지 않습니다 — 스냅샷보다 먼저 기록된 입장 · 이탈을 WAITING 으로 되돌리면 안 됩니다 */
    @Override
    public void upsertWaiting(long concertId, List<QueueMember> members) {
        if (members.isEmpty()) return;
        jpa.upsertIfNewer(concertId, QueueEntryEntity.Status.WAITING.name(), null,
                userIds(members), seqs(members), new String[members.size()]);
    }

    @Override
    public void markAdmitted(long concertId, List<AdmittedMember> members, OffsetDateTime at) {
        if (members.isEmpty()) return;
        jpa.upsertIfNotOlder(concertId, QueueEntryEntity.Status.ADMITTED.name(), at,
                members.stream().map(AdmittedMember::userId).toArray(Long[]::new),
                members.stream().map(AdmittedMember::seq).toArray(Long[]::new),
                members.stream().map(AdmittedMember::entryToken).toArray(String[]::new));
    }

    /** WAITING · LEFT 는 토큰을 비웁니다 */
    @Override
    public void markLeft(long concertId, List<QueueMember> members) {
        if (members.isEmpty()) return;
        jpa.upsertIfNotOlder(concertId, QueueEntryEntity.Status.LEFT.name(), null,
                userIds(members), seqs(members), new String[members.size()]);
    }

    @Override
    public void markLeft(long concertId, long userId) {
        jpa.markLeft(concertId, userId);
    }

    @Override
    public List<ActiveEntry> findAdmittedSince(long concertId, OffsetDateTime since) {
        return jpa.findByConcertIdAndStatusAndAdmittedAtAfterAndEntryTokenIsNotNull(
                concertId, QueueEntryEntity.Status.ADMITTED, since);
    }

    @Override
    public List<QueueMember> findWaitingAfter(long concertId, long afterSeq, int limit) {
        return jpa.findByConcertIdAndStatusAndSeqGreaterThanOrderBySeq(
                concertId, QueueEntryEntity.Status.WAITING, afterSeq, Limit.of(limit));
    }

    @Override
    public long maxSeq(long concertId) {
        return jpa.maxSeq(concertId);
    }

    @Override
    public Optional<Position> findPosition(long concertId, long userId) {
        return jpa.findByConcertIdAndUserId(concertId, userId);
    }

    @Override
    public long countWaitingBefore(long concertId, long seq) {
        return jpa.countByConcertIdAndStatusAndSeqLessThan(concertId, QueueEntryEntity.Status.WAITING, seq);
    }

    @Override
    public long countWaiting(long concertId) {
        return jpa.countByConcertIdAndStatus(concertId, QueueEntryEntity.Status.WAITING);
    }

    private static Long[] userIds(List<QueueMember> members) {
        return members.stream().map(QueueMember::userId).toArray(Long[]::new);
    }

    private static Long[] seqs(List<QueueMember> members) {
        return members.stream().map(QueueMember::seq).toArray(Long[]::new);
    }
}
