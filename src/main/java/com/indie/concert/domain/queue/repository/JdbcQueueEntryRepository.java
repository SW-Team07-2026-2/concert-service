package com.indie.concert.domain.queue.repository;

import com.indie.concert.domain.queue.entity.AdmittedMember;
import com.indie.concert.domain.queue.entity.QueueEntryEntity;
import com.indie.concert.domain.queue.entity.QueueMember;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * queue_entry 배치 upsert — 1만 명이 들어와도 DB 에는 5초마다 배치 한 번 (16 §1).
 * 개별 INSERT 1만 번은 커밋 · 왕복이 1만 번이라 불가능합니다.
 */
@Repository
public class JdbcQueueEntryRepository implements QueueEntryRepository {

    private static final String UPSERT = """
            INSERT INTO queue_entry (concert_id, user_id, seq, status, admitted_at, entry_token) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (concert_id, user_id) DO UPDATE
               SET seq = EXCLUDED.seq, status = EXCLUDED.status, admitted_at = EXCLUDED.admitted_at,
                   entry_token = EXCLUDED.entry_token,
                   entered_at = CASE WHEN queue_entry.seq < EXCLUDED.seq THEN now() ELSE queue_entry.entered_at END
             WHERE queue_entry.seq %s EXCLUDED.seq
            """;

    private final JdbcTemplate jdbc;

    public JdbcQueueEntryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 같은 seq 는 덮어쓰지 않습니다 — 스냅샷보다 먼저 기록된 입장 · 이탈을 WAITING 으로 되돌리면 안 됩니다 */
    @Override
    public void upsertWaiting(long concertId, List<QueueMember> members) {
        batch(UPSERT.formatted("<"), concertId, rows(members), QueueEntryEntity.Status.WAITING, null);
    }

    @Override
    public void markAdmitted(long concertId, List<AdmittedMember> members, OffsetDateTime at) {
        batch(UPSERT.formatted("<="), concertId, members.stream().map(m -> new Row(m.userId(), m.seq(), m.entryToken())).toList(),
                QueueEntryEntity.Status.ADMITTED, at);
    }

    @Override
    public void markLeft(long concertId, List<QueueMember> members) {
        batch(UPSERT.formatted("<="), concertId, rows(members), QueueEntryEntity.Status.LEFT, null);
    }

    @Override
    public void markLeft(long concertId, long userId) {
        jdbc.update("""
                UPDATE queue_entry SET status = 'LEFT', entry_token = NULL
                WHERE concert_id = ? AND user_id = ? AND status <> 'LEFT'
                """, concertId, userId);
    }

    @Override
    public List<ActiveEntry> findAdmittedSince(long concertId, OffsetDateTime since) {
        return jdbc.query("""
                SELECT user_id, entry_token, admitted_at FROM queue_entry
                WHERE concert_id = ? AND status = 'ADMITTED' AND admitted_at > ? AND entry_token IS NOT NULL
                """, (rs, i) -> new ActiveEntry(rs.getLong("user_id"), rs.getString("entry_token"),
                rs.getObject("admitted_at", OffsetDateTime.class)), concertId, since);
    }

    @Override
    public List<QueueMember> findWaitingAfter(long concertId, long afterSeq, int limit) {
        return jdbc.query("""
                SELECT user_id, seq FROM queue_entry
                WHERE concert_id = ? AND status = 'WAITING' AND seq > ?
                ORDER BY seq LIMIT ?
                """, (rs, i) -> new QueueMember(rs.getLong("user_id"), rs.getLong("seq")), concertId, afterSeq, limit);
    }

    @Override
    public long maxSeq(long concertId) {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(seq), 0) FROM queue_entry WHERE concert_id = ?", Long.class, concertId);
        return max == null ? 0 : max;
    }

    @Override
    public Optional<Position> findPosition(long concertId, long userId) {
        return jdbc.query("SELECT status, seq FROM queue_entry WHERE concert_id = ? AND user_id = ?",
                (rs, i) -> new Position(QueueEntryEntity.Status.valueOf(rs.getString("status")), rs.getLong("seq")),
                concertId, userId).stream().findFirst();
    }

    @Override
    public long countWaitingBefore(long concertId, long seq) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM queue_entry WHERE concert_id = ? AND status = 'WAITING' AND seq < ?",
                Long.class, concertId, seq);
        return count == null ? 0 : count;
    }

    @Override
    public long countWaiting(long concertId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM queue_entry WHERE concert_id = ? AND status = 'WAITING'",
                Long.class, concertId);
        return count == null ? 0 : count;
    }

    private void batch(String sql, long concertId, List<Row> rows, QueueEntryEntity.Status status, OffsetDateTime admittedAt) {
        if (rows.isEmpty()) return;
        jdbc.batchUpdate(sql, rows, 500, (ps, r) -> {
            ps.setLong(1, concertId);
            ps.setLong(2, r.userId());
            ps.setLong(3, r.seq());
            ps.setString(4, status.name());
            ps.setObject(5, admittedAt);
            ps.setString(6, r.entryToken());
        });
    }

    /** WAITING · LEFT 는 토큰을 비웁니다 */
    private static List<Row> rows(List<QueueMember> members) {
        return members.stream().map(m -> new Row(m.userId(), m.seq(), null)).toList();
    }

    private record Row(long userId, long seq, String entryToken) {}
}
