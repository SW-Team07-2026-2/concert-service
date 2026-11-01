package com.indie.concert.domain.booking.repository;

import com.indie.concert.domain.booking.entity.BookingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingJpaRepository extends JpaRepository<BookingEntity, Long>, BookingRepository {

    @Override
    @Query(value = """
            SELECT * FROM booking WHERE concert_id = :concertId AND user_id = :userId AND status IN ('PENDING', 'CONFIRMED')
            LIMIT 1
            """, nativeQuery = true)
    Optional<BookingEntity> findActive(@Param("concertId") long concertId, @Param("userId") long userId);

    @Override
    @Modifying
    @Query(value = "UPDATE booking SET pg_order_id = :pgOrderId WHERE id = :id", nativeQuery = true)
    int attachPgOrderId(@Param("id") long id, @Param("pgOrderId") String pgOrderId);

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
            WHERE id = :id AND status = 'CONFIRMED' AND checked_in_at IS NULL
            """, nativeQuery = true)
    int checkIn(@Param("id") long id, @Param("checkerId") long checkerId, @Param("at") OffsetDateTime at);

    /** 승인 중(seat HELD) 이 아니면 고른 시각, 승인 중이면 claim 만료 시각이 기준 (idx_booking_pending · idx_seat_booking) */
    @Override
    @Query(value = """
            SELECT b.id FROM booking b LEFT JOIN seat s ON s.booking_id = b.id AND s.status = 'HELD'
            WHERE b.status = 'PENDING'
              AND ((s.id IS NULL AND b.created_at < :selectedBefore) OR s.hold_expires_at < :now)
            ORDER BY b.created_at LIMIT :limit
            """, nativeQuery = true)
    List<Long> findExpiredPending(@Param("now") OffsetDateTime now, @Param("selectedBefore") OffsetDateTime selectedBefore,
                                  @Param("limit") int limit);

    @Override
    @Query(value = """
            SELECT DISTINCT bs.seat_id FROM booking b JOIN booking_seat bs ON bs.booking_id = b.id
            WHERE b.concert_id = :concertId AND b.status = 'PENDING' AND b.created_at > :createdAfter
            """, nativeQuery = true)
    List<Long> findPendingSeatIds(@Param("concertId") long concertId, @Param("createdAfter") OffsetDateTime createdAfter);
}
