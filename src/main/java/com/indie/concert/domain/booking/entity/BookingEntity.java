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
 * PENDING ──결제 승인──▶ CONFIRMED ──취소──▶ CANCELLED
 *    ├── 결제 실패 · 취소 ──▶ CANCELLED
 *    └── 점유 3분 경과 ────▶ EXPIRED        (08 §8.6 — ERD 에 없던 상태)
 * </pre>
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
    public OffsetDateTime getCheckedInAt() { return checkedInAt; }
}
