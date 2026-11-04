package com.indie.concert.domain.queue.repository;

import com.indie.concert.domain.queue.entity.QueueEntryEntity;
import com.indie.concert.domain.queue.entity.QueueMember;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 쓰기는 QueueService 가 트랜잭션 없이 부르므로 (JDBC 때와 같게) 각 문장이 스스로 트랜잭션을 엽니다 — 있으면 합류 */
public interface QueueEntryJpaRepository extends JpaRepository<QueueEntryEntity, Long> {

    /**
     * 배치 upsert 한 문장 — 배열 3개를 unnest 로 행으로 펼칩니다 (1만 명도 왕복 1번).
     * 기존 seq 보다 <b>클 때만</b> 덮어씁니다 (스냅샷 · 재진입).
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO queue_entry (concert_id, user_id, seq, status, admitted_at, entry_token)
            SELECT :concertId, r.user_id, r.seq, :status, CAST(:admittedAt AS timestamptz), r.entry_token
            FROM unnest(CAST(:userIds AS bigint[]), CAST(:seqs AS bigint[]), CAST(:tokens AS varchar[])) AS r(user_id, seq, entry_token)
            ON CONFLICT (concert_id, user_id) DO UPDATE
               SET seq = EXCLUDED.seq, status = EXCLUDED.status, admitted_at = EXCLUDED.admitted_at,
                   entry_token = EXCLUDED.entry_token,
                   entered_at = CASE WHEN queue_entry.seq < EXCLUDED.seq THEN now() ELSE queue_entry.entered_at END
             WHERE queue_entry.seq < EXCLUDED.seq
            """, nativeQuery = true)
    int upsertIfNewer(@Param("concertId") long concertId, @Param("status") String status,
                      @Param("admittedAt") OffsetDateTime admittedAt, @Param("userIds") Long[] userIds,
                      @Param("seqs") Long[] seqs, @Param("tokens") String[] tokens);

    /** upsertIfNewer 와 같고 seq 가 <b>같아도</b> 덮어씁니다 (입장 · 이탈 — 같은 순번의 상태 변화) */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO queue_entry (concert_id, user_id, seq, status, admitted_at, entry_token)
            SELECT :concertId, r.user_id, r.seq, :status, CAST(:admittedAt AS timestamptz), r.entry_token
            FROM unnest(CAST(:userIds AS bigint[]), CAST(:seqs AS bigint[]), CAST(:tokens AS varchar[])) AS r(user_id, seq, entry_token)
            ON CONFLICT (concert_id, user_id) DO UPDATE
               SET seq = EXCLUDED.seq, status = EXCLUDED.status, admitted_at = EXCLUDED.admitted_at,
                   entry_token = EXCLUDED.entry_token,
                   entered_at = CASE WHEN queue_entry.seq < EXCLUDED.seq THEN now() ELSE queue_entry.entered_at END
             WHERE queue_entry.seq <= EXCLUDED.seq
            """, nativeQuery = true)
    int upsertIfNotOlder(@Param("concertId") long concertId, @Param("status") String status,
                         @Param("admittedAt") OffsetDateTime admittedAt, @Param("userIds") Long[] userIds,
                         @Param("seqs") Long[] seqs, @Param("tokens") String[] tokens);

    @Modifying
    @Transactional
    @Query("""
            UPDATE QueueEntryEntity e SET e.status = 'LEFT', e.entryToken = NULL
            WHERE e.concertId = :concertId AND e.userId = :userId AND e.status <> 'LEFT'
            """)
    int markLeft(@Param("concertId") long concertId, @Param("userId") long userId);

    List<QueueEntryRepository.ActiveEntry> findByConcertIdAndStatusAndAdmittedAtAfterAndEntryTokenIsNotNull(
            long concertId, QueueEntryEntity.Status status, OffsetDateTime since);

    /** (user_id, seq) 만 — 커버링 인덱스 idx_queue_rebuild 로 Index Only Scan */
    List<QueueMember> findByConcertIdAndStatusAndSeqGreaterThanOrderBySeq(
            long concertId, QueueEntryEntity.Status status, long seq, Limit limit);

    @Query("SELECT COALESCE(MAX(e.seq), 0) FROM QueueEntryEntity e WHERE e.concertId = :concertId")
    long maxSeq(@Param("concertId") long concertId);

    Optional<QueueEntryRepository.Position> findByConcertIdAndUserId(long concertId, long userId);

    long countByConcertIdAndStatusAndSeqLessThan(long concertId, QueueEntryEntity.Status status, long seq);

    long countByConcertIdAndStatus(long concertId, QueueEntryEntity.Status status);
}
