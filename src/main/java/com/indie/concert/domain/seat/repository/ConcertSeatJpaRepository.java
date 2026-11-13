package com.indie.concert.domain.seat.repository;

import com.indie.concert.domain.seat.entity.ConcertSeatEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;


public interface ConcertSeatJpaRepository extends JpaRepository<ConcertSeatEntity, Long>, ConcertSeatRepository {

    @Override
    @Modifying
    @Query(value = """
            UPDATE concert_seat SET status = 'HELD', held_by = :userId, booking_id = :bookingId,
                   hold_expires_at = (SELECT hold_expires_at FROM booking WHERE id = :bookingId)
            WHERE id = :concertSeatId AND concert_id = :concertId
              AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at < now()))
            """, nativeQuery = true)
    int hold(@Param("concertSeatId") long concertSeatId, @Param("concertId") long concertId,
             @Param("bookingId") long bookingId, @Param("userId") long userId);

    @Override
    @Modifying
    @Query(value = """
            UPDATE concert_seat SET status = 'PAYING'
            WHERE booking_id = :bookingId AND held_by = :userId AND status = 'HELD' AND hold_expires_at > now()
            """, nativeQuery = true)
    int startPaying(@Param("bookingId") long bookingId, @Param("userId") long userId);

    @Override
    @Modifying
    @Query(value = "UPDATE concert_seat SET status = 'HELD' WHERE booking_id = :bookingId AND status = 'PAYING'",
            nativeQuery = true)
    int stopPaying(@Param("bookingId") long bookingId);

    @Override
    @Modifying
    @Query(value = """
            UPDATE concert_seat SET status = 'SOLD', hold_expires_at = NULL WHERE booking_id = :bookingId AND status = 'PAYING'
            """, nativeQuery = true)
    int sell(@Param("bookingId") long bookingId);

    @Override
    @Modifying
    @Query(value = """
            UPDATE concert_seat SET status = 'AVAILABLE', held_by = NULL, hold_expires_at = NULL, booking_id = NULL
            WHERE booking_id = :bookingId
            """, nativeQuery = true)
    int releaseBooking(@Param("bookingId") long bookingId);

    @Override
    @Query(value = "SELECT CAST(EXTRACT(EPOCH FROM now()) * 1000 AS BIGINT)", nativeQuery = true)
    long nowMillis();

    /** uq_concert_seat (concert_id 로 시작) 로 이 공연 좌석만 읽고 seat 는 PK 로 붙입니다 */
    @Override
    @Query(value = """
            SELECT cs.id AS id, s.section_id AS sectionId, s.row_no AS rowNo, s.seat_no AS seatNo,
                   s.x AS x, s.y AS y, s.angle AS angle, cs.price AS price,
                   CASE WHEN cs.status = 'SOLD' THEN 'SOLD'
                        WHEN cs.status = 'PAYING' THEN 'HELD'
                        WHEN cs.status = 'HELD' AND cs.hold_expires_at > now() THEN 'HELD'
                        ELSE 'AVAILABLE' END AS status
            FROM concert_seat cs JOIN seat s ON s.id = cs.seat_id
            WHERE cs.concert_id = :concertId
            ORDER BY s.section_id, s.row_no, s.seat_no
            """, nativeQuery = true)
    List<SeatMapRow> findSeatMap(@Param("concertId") long concertId);

    @Override
    @Query(value = """
            SELECT cs.id AS id, f.name AS floor, sc.name AS section, s.row_no AS rowNo, s.seat_no AS seatNo, cs.price AS price
            FROM concert_seat cs JOIN seat s ON s.id = cs.seat_id JOIN section sc ON sc.id = s.section_id
                 JOIN floor f ON f.id = sc.floor_id
            WHERE cs.id IN (:ids)
            ORDER BY f.name, sc.name, s.row_no, s.seat_no
            """, nativeQuery = true)
    List<SeatRefRow> findRefs(@Param("ids") Collection<Long> concertSeatIds);

    @Override
    @Query(value = """
            SELECT count(*) FROM seat s JOIN section sc ON sc.id = s.section_id JOIN floor f ON f.id = sc.floor_id
            WHERE f.venue_id = :venueId AND s.id = ANY(CAST(:seatIds AS bigint[]))
            """, nativeQuery = true)
    long countVenueSeats(@Param("venueId") long venueId, @Param("seatIds") Long[] seatIds);

    /** 한 문장 — 좌석 1만 개도 왕복 1번 (IDENTITY 라 Hibernate 배치 INSERT 가 꺼져 있습니다) */
    @Override
    @Modifying
    @Query(value = """
            INSERT INTO concert_seat (concert_id, seat_id, price, status)
            SELECT :concertId, r.seat_id, r.price, 'AVAILABLE'
            FROM unnest(CAST(:seatIds AS bigint[]), CAST(:prices AS int[])) AS r(seat_id, price)
            ON CONFLICT (concert_id, seat_id) DO UPDATE SET price = EXCLUDED.price
             WHERE concert_seat.status = 'AVAILABLE'
            """, nativeQuery = true)
    int upsertPrices(@Param("concertId") long concertId, @Param("seatIds") Long[] seatIds,
                     @Param("prices") Integer[] prices);
}
