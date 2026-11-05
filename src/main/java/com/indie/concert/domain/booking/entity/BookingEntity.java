package com.indie.concert.domain.booking.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * booking — 상태 전이는 BookingRepository 의 조건부 UPDATE 로만 합니다 (중복 이벤트에도 한 번만 전이).
 * <pre>
 * PENDING(= 선점) ──결제 승인──▶ CONFIRMED ──취소──▶ CANCELLED
 *    ├── 선택 취소 ──▶ CANCELLED
 *    └── 선점 마감(서버 9분) 뒤 정리 ▶ EXPIRED     (08 §8.6 — ERD 에 없던 상태)
 * </pre>
 * 11/04 77A — 예매 PENDING 이 곧 선점(holdId = bookingId)입니다. 결제가 거절돼도 PENDING 그대로 (새 결제로 다시 · 80B).
 * 입장(검표)은 상태가 아니라 checked_in_at 으로 남깁니다 — CONFIRMED 그대로, 한 번만 기록 (11/01).
 */
@Entity
@Table(name = "booking")
public class BookingEntity {

    public enum Status { PENDING, CONFIRMED, CANCELLED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private Long concertId;

    @Enumerated(EnumType.STRING)
    private Status status;

    private Integer totalPrice;

    private String pgOrderId;

    private OffsetDateTime createdAt;

    /** 선점 서버 마감 — N10 때 DB now() + 9분 (화면 마감은 60초 앞) */
    private OffsetDateTime holdExpiresAt;

    /** 결제 승인 시작 (좌석 PAYING) — 응답을 못 받으면 1분 뒤 정리 */
    private OffsetDateTime payingAt;

    private OffsetDateTime checkedInAt;

    private Long checkedInBy;

    protected BookingEntity() {}

    public BookingEntity(Long userId, Long concertId, Integer totalPrice, OffsetDateTime now) {
        this.userId = userId;
        this.concertId = concertId;
        this.status = Status.PENDING;
        this.totalPrice = totalPrice;
        this.createdAt = now;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getConcertId() { return concertId; }
    public Status getStatus() { return status; }
    public Integer getTotalPrice() { return totalPrice; }
    public String getPgOrderId() { return pgOrderId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getHoldExpiresAt() { return holdExpiresAt; }
    public OffsetDateTime getPayingAt() { return payingAt; }
    public OffsetDateTime getCheckedInAt() { return checkedInAt; }
}
