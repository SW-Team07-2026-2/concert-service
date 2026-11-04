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

/**
 * seat — 상태 전이는 SeatRepository 의 조건부 UPDATE 로만 합니다 (FR-CON-27 — 좌석 중복의 최종 보장).
 * <pre>
 * AVAILABLE ──결제 승인 요청(N16) · 먼저 온 한 명──▶ HELD(승인 중 · 수 초) ──승인──▶ SOLD
 *     ▲                                              │                          │
 *     └──────── 거절 · 승인 전 거부 · 결제 무효화 ───────┘                          │
 *     └──────────────────────────── 확정 예매 취소 ─────────────────────────────┘
 * </pre>
 * 좌석을 고르는 것(N10)은 좌석을 바꾸지 않습니다 — 여러 명이 같은 좌석을 고른 "경합"은 PENDING 예매로만 보입니다 (10/27).
 * HELD 좌석은 승인 응답을 못 받았어도 다른 사람이 가져가지 못하고, 만료 처리기가 결제 결과를 확인한 뒤에만 풀립니다.
 */
@Entity
@Table(name = "seat", uniqueConstraints = @UniqueConstraint(name = "uq_seat_position",
        columnNames = {"concert_id", "section", "row_no", "seat_no"}))
public class SeatEntity {

    public enum Status { AVAILABLE, HELD, SOLD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long concertId;

    private String section;

    private String rowNo;

    private Integer seatNo;

    private Integer price;

    @Enumerated(EnumType.STRING)
    private Status status;

    private Long heldBy;

    private OffsetDateTime holdExpiresAt;

    private Long bookingId;

    protected SeatEntity() {}

    public Long getId() { return id; }
    public Long getConcertId() { return concertId; }
    public String getSection() { return section; }
    public String getRowNo() { return rowNo; }
    public Integer getSeatNo() { return seatNo; }
    public Integer getPrice() { return price; }
    public Status getStatus() { return status; }
    public Long getHeldBy() { return heldBy; }
    public OffsetDateTime getHoldExpiresAt() { return holdExpiresAt; }
    public Long getBookingId() { return bookingId; }
}
