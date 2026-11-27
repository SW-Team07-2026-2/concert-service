package com.indie.concert.domain.booking.repository;

import com.indie.concert.domain.booking.entity.BookingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface BookingJpaRepository extends JpaRepository<BookingEntity, Long>, BookingRepository {

    @Override
    @Query(value = """
            SELECT * FROM booking WHERE concert_id = :concertId AND user_id = :userId AND status IN ('PENDING', 'CONFIRMED')
            ORDER BY id
            """, nativeQuery = true)
    List<BookingEntity> findActive(@Param("concertId") long concertId, @Param("userId") long userId);

    @Override
    @Modifying
    @Query(value = "UPDATE booking SET pg_order_id = :pgOrderId WHERE id = :id", nativeQuery = true)
    int attachPgOrderId(@Param("id") long id, @Param("pgOrderId") String pgOrderId);

    @Override
    @Modifying
    @Query(value = "UPDATE booking SET hold_expires_at = now() + make_interval(secs => :seconds) WHERE id = :id", nativeQuery = true)
    int startHold(@Param("id") long id, @Param("seconds") long seconds);

    @Override
    @Modifying
    @Query(value = "UPDATE booking SET paying_at = now() WHERE id = :id AND status = 'PENDING'", nativeQuery = true)
    int markPaying(@Param("id") long id);

    @Override
    @Modifying
    @Query(value = "UPDATE booking SET paying_at = NULL WHERE id = :id", nativeQuery = true)
    int clearPaying(@Param("id") long id);

    @Override
    @Query(value = "SELECT status FROM booking WHERE id = :id FOR UPDATE", nativeQuery = true)
    String lockStatus(@Param("id") long id);

    @Override
    @Query(value = "SELECT user_id FROM booking WHERE concert_id = :concertId AND status = 'CONFIRMED' ORDER BY user_id", nativeQuery = true)
    List<Long> findConfirmedUserIds(@Param("concertId") long concertId);

    @Override
    @Modifying
    @Query(value = "UPDATE booking SET status = 'CONFIRMED' WHERE id = :id AND status = 'PENDING'", nativeQuery = true)
    int confirm(@Param("id") long id);

    @Override
    @Modifying
    @Query(value = "UPDATE booking SET status = 'CANCELLED' WHERE id = :id AND status IN ('PENDING', 'CONFIRMED')",
            nativeQuery = true)
    int cancel(@Param("id") long id);

    @Override
    @Modifying
    @Query(value = "UPDATE booking SET status = 'EXPIRED' WHERE id = :id AND status = 'PENDING'", nativeQuery = true)
    int expire(@Param("id") long id);

    @Override
    @Modifying
    @Query(value = """
            UPDATE booking SET checked_in_at = :at, checked_in_by = :checkerId
            WHERE id = :id AND status = 'CONFIRMED' AND checked_in_at IS NULL AND withdrawn_at IS NULL
            """, nativeQuery = true)
    int checkIn(@Param("id") long id, @Param("checkerId") long checkerId, @Param("at") OffsetDateTime at);

    @Override
    @Modifying
    @Query(value = """
            UPDATE booking SET withdrawn_at = :at
            WHERE user_id = :userId AND status IN ('PENDING', 'CONFIRMED') AND checked_in_at IS NULL AND withdrawn_at IS NULL
            """, nativeQuery = true)
    int markWithdrawn(@Param("userId") long userId, @Param("at") OffsetDateTime at);

    @Override
    @Query(value = """
            SELECT id FROM booking WHERE user_id = :userId AND withdrawn_at IS NOT NULL AND status IN ('PENDING', 'CONFIRMED')
            ORDER BY id
            """, nativeQuery = true)
    List<Long> findWithdrawnIds(@Param("userId") long userId);

    @Override
    @Query(value = """
            SELECT id FROM booking WHERE withdrawn_at IS NOT NULL AND status IN ('PENDING', 'CONFIRMED')
            ORDER BY id LIMIT :limit
            """, nativeQuery = true)
    List<Long> findWithdrawnOpen(@Param("limit") int limit);

    @Override
    @Modifying
    @Query(value = """
            UPDATE booking SET status = 'EXPIRED'
            WHERE id = :id AND status = 'CONFIRMED' AND checked_in_at IS NULL AND withdrawn_at IS NOT NULL
            """, nativeQuery = true)
    int forfeit(@Param("id") long id);

    @Override
    @Query(value = "SELECT EXISTS (SELECT 1 FROM booking WHERE session_id = :sessionId)", nativeQuery = true)
    boolean existsBySession(@Param("sessionId") long sessionId);

    @Override
    @Query(value = """
            SELECT id FROM booking
            WHERE status = 'PENDING' AND paying_at IS NULL AND hold_expires_at < now()
            ORDER BY hold_expires_at LIMIT :limit
            """, nativeQuery = true)
    List<Long> findExpiredHolds(@Param("limit") int limit);

    @Override
    @Query(value = """
            SELECT id FROM booking
            WHERE status = 'PENDING' AND paying_at < now() - make_interval(secs => :seconds)
            ORDER BY paying_at LIMIT :limit
            """, nativeQuery = true)
    List<Long> findStalePaying(@Param("seconds") long seconds, @Param("limit") int limit);
}
