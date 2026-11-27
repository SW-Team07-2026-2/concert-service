package com.indie.concert.domain.seat.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;

/** 회차별 판매 좌석의 가격과 AVAILABLE, HELD, PAYING, SOLD 상태를 관리합니다. 상태는 조건부 UPDATE로 변경합니다. */
@Entity
@Table(name = "concert_seat", uniqueConstraints = @UniqueConstraint(name = "uq_concert_seat",
        columnNames = {"concert_id", "session_id", "seat_id"}))
public class ConcertSeatEntity {

    public enum Status { AVAILABLE, HELD, PAYING, SOLD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long concertId;

    /** 회차 - 회차마다 좌석 한 벌 */
    private Long sessionId;

    private Long seatId;

    private Integer price;

    @Enumerated(EnumType.STRING)
    private Status status;

    private Long heldBy;

    private OffsetDateTime holdExpiresAt;

    private Long bookingId;

    protected ConcertSeatEntity() {}

    public Long getId() { return id; }
    public Long getConcertId() { return concertId; }
    public Long getSessionId() { return sessionId; }
    public Long getSeatId() { return seatId; }
    public Integer getPrice() { return price; }
    public Status getStatus() { return status; }
    public Long getHeldBy() { return heldBy; }
    public OffsetDateTime getHoldExpiresAt() { return holdExpiresAt; }
    public Long getBookingId() { return bookingId; }
}
