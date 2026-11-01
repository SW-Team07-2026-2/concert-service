package com.indie.concert.domain.seat.repository;

import com.indie.concert.domain.seat.entity.SeatEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface SeatJpaRepository extends JpaRepository<SeatEntity, Long>, SeatRepository {

    @Override
    @Modifying
    @Query(value = """
            UPDATE seat SET status = 'HELD', held_by = :userId, booking_id = :bookingId, hold_expires_at = :until
            WHERE id = :seatId AND status = 'AVAILABLE'
              AND EXISTS (SELECT 1 FROM booking WHERE id = :bookingId AND status = 'PENDING')
            """, nativeQuery = true)
    int claim(@Param("seatId") long seatId, @Param("bookingId") long bookingId, @Param("userId") long userId,
              @Param("until") OffsetDateTime until);

    @Override
    @Modifying
    @Query(value = """
            UPDATE seat SET status = 'AVAILABLE', held_by = NULL, hold_expires_at = NULL, booking_id = NULL
            WHERE booking_id = :bookingId AND status = 'HELD'
            """, nativeQuery = true)
    int unclaim(@Param("bookingId") long bookingId);

    @Override
    @Modifying
    @Query(value = "UPDATE seat SET status = 'SOLD', hold_expires_at = NULL WHERE booking_id = :bookingId AND status = 'HELD'",
            nativeQuery = true)
    int sell(@Param("bookingId") long bookingId);

    @Override
    @Modifying
    @Query(value = """
            UPDATE seat SET status = 'AVAILABLE', held_by = NULL, hold_expires_at = NULL, booking_id = NULL
            WHERE booking_id = :bookingId
            """, nativeQuery = true)
    int releaseBooking(@Param("bookingId") long bookingId);

    /** generate_series 한 문장 — 좌석 1만 개도 왕복 1번 (12.4 — 대량 생성은 Concert 쪽에서) */
    @Override
    @Modifying
    @Query(value = """
            INSERT INTO seat (concert_id, section, row_no, seat_no, price, status)
            SELECT :concertId, :section, CAST(r AS VARCHAR), s, :price, 'AVAILABLE'
            FROM generate_series(1, :rowCount) AS r, generate_series(1, :seatsPerRow) AS s
            ON CONFLICT (concert_id, section, row_no, seat_no) DO NOTHING
            """, nativeQuery = true)
    int createGrid(@Param("concertId") long concertId, @Param("section") String section, @Param("rowCount") int rows,
                   @Param("seatsPerRow") int seatsPerRow, @Param("price") int price);
}
