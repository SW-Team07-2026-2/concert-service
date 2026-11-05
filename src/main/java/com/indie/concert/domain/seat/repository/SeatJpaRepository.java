package com.indie.concert.domain.seat.repository;

import com.indie.concert.domain.seat.entity.SeatEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


public interface SeatJpaRepository extends JpaRepository<SeatEntity, Long>, SeatRepository {

    @Override
    @Modifying
    @Query(value = """
            UPDATE seat SET status = 'HELD', held_by = :userId, booking_id = :bookingId,
                   hold_expires_at = (SELECT hold_expires_at FROM booking WHERE id = :bookingId)
            WHERE id = :seatId AND concert_id = :concertId
              AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at < now()))
            """, nativeQuery = true)
    int hold(@Param("seatId") long seatId, @Param("concertId") long concertId, @Param("bookingId") long bookingId,
             @Param("userId") long userId);

    @Override
    @Modifying
    @Query(value = """
            UPDATE seat SET status = 'PAYING'
            WHERE booking_id = :bookingId AND held_by = :userId AND status = 'HELD' AND hold_expires_at > now()
            """, nativeQuery = true)
    int startPaying(@Param("bookingId") long bookingId, @Param("userId") long userId);

    @Override
    @Modifying
    @Query(value = "UPDATE seat SET status = 'HELD' WHERE booking_id = :bookingId AND status = 'PAYING'", nativeQuery = true)
    int stopPaying(@Param("bookingId") long bookingId);

    @Override
    @Modifying
    @Query(value = "UPDATE seat SET status = 'SOLD', hold_expires_at = NULL WHERE booking_id = :bookingId AND status = 'PAYING'",
            nativeQuery = true)
    int sell(@Param("bookingId") long bookingId);

    @Override
    @Modifying
    @Query(value = """
            UPDATE seat SET status = 'AVAILABLE', held_by = NULL, hold_expires_at = NULL, booking_id = NULL
            WHERE booking_id = :bookingId
            """, nativeQuery = true)
    int releaseBooking(@Param("bookingId") long bookingId);

    @Override
    @Query(value = "SELECT CAST(EXTRACT(EPOCH FROM now()) * 1000 AS BIGINT)", nativeQuery = true)
    long nowMillis();

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
