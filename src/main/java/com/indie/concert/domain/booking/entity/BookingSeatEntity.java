package com.indie.concert.domain.booking.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/** booking_seat — 예매가 잡았던 공연 좌석 (1인 N매면 N행). concert_seat.booking_id 는 취소되면 비워지므로 "어느 좌석이었나"는 여기 남습니다 */
@Entity
@Table(name = "booking_seat")
@IdClass(BookingSeatEntity.Key.class)
public class BookingSeatEntity {

    @Id
    private Long bookingId;

    @Id
    private Long concertSeatId;

    protected BookingSeatEntity() {}

    public BookingSeatEntity(Long bookingId, Long concertSeatId) {
        this.bookingId = bookingId;
        this.concertSeatId = concertSeatId;
    }

    public Long getBookingId() { return bookingId; }
    public Long getConcertSeatId() { return concertSeatId; }

    public static class Key implements Serializable {
        private Long bookingId;
        private Long concertSeatId;

        public Key() {}

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(bookingId, k.bookingId) && Objects.equals(concertSeatId, k.concertSeatId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(bookingId, concertSeatId);
        }
    }
}
