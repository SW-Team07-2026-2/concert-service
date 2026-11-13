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
 * concert_seat — 공연 하나의 좌석 하나 (판매 단위). 위치는 seat_id 로 공연장 좌석(seat)을 가리키고, 가격 · 상태는 여기에.
 * 상태 전이는 ConcertSeatRepository 의 조건부 UPDATE 로만 합니다 (좌석 중복 판매의 최종 보장).
 * <pre>
 * AVAILABLE ──좌석 선택 · 한 명──▶ HELD(선점) ──결제 승인 요청──▶ PAYING(승인 중) ──승인──▶ SOLD
 *     ▲                                  │   ▲                                    │                     │
 *     │                                  │   └──── 거절 · 승인 전 거부 (마감 그대로) ──┘                     │
 *     └── 선택 취소 · 마감 뒤 정리 ─────────┘                                                              │
 *     └─────────────────────────────────────── 확정 예매 취소 ───────────────────────────────────────────┘
 * </pre>
 * 11/04 77A — 마감(hold_expires_at)이 지난 HELD 는 정리를 기다리지 않고 다음 사람이 바로 선점합니다.
 * PAYING 은 마감이 지나도 다른 사람이 가져가지 못하고, 정리가 결제 결과를 확인한 뒤에만 풀립니다.
 * 예매 하나가 좌석 여러 개(1인 N매)를 잡아도 마감은 예매의 hold_expires_at 하나라 전부 같이 움직입니다.
 */
@Entity
@Table(name = "concert_seat", uniqueConstraints = @UniqueConstraint(name = "uq_concert_seat",
        columnNames = {"concert_id", "seat_id"}))
public class ConcertSeatEntity {

    public enum Status { AVAILABLE, HELD, PAYING, SOLD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long concertId;

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
    public Long getSeatId() { return seatId; }
    public Integer getPrice() { return price; }
    public Status getStatus() { return status; }
    public Long getHeldBy() { return heldBy; }
    public OffsetDateTime getHoldExpiresAt() { return holdExpiresAt; }
    public Long getBookingId() { return bookingId; }
}
