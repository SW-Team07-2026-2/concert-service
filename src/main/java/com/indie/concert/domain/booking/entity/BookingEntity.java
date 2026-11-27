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
 * 회차별 예매와 선점 상태. 상태는 저장소의 조건부 UPDATE로 변경합니다.
 * 입장은 checked_in_at으로 기록하고 탈퇴 시 환불 또는 만료 규칙을 적용합니다.
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

    /** 예매한 회차 - 1인 1예매, 입장 QR 시간, 취소 마감이 이 회차 기준 */
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    private Status status;

    private Integer totalPrice;

    private String pgOrderId;

    private OffsetDateTime createdAt;

    /** 선점 서버 마감 - N10 때 DB now() + 입장 토큰 남은 초 + 유예 179초 (화면 마감은 179초 앞) */
    private OffsetDateTime holdExpiresAt;

    /** 결제 승인 시작 (좌석 PAYING) - 응답을 못 받으면 1분 뒤 정리 */
    private OffsetDateTime payingAt;

    private OffsetDateTime checkedInAt;

    private Long checkedInBy;

    /** 회원 탈퇴 - 정리(환불, 만료) 표시. 24시간 기준은 이 시각으로 봅니다 (재시도가 늦어져도 결과가 바뀌지 않게) */
    private OffsetDateTime withdrawnAt;

    protected BookingEntity() {}

    public BookingEntity(Long userId, Long concertId, Long sessionId, Integer totalPrice, OffsetDateTime now) {
        this.userId = userId;
        this.concertId = concertId;
        this.sessionId = sessionId;
        this.status = Status.PENDING;
        this.totalPrice = totalPrice;
        this.createdAt = now;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getConcertId() { return concertId; }
    public Long getSessionId() { return sessionId; }
    public Status getStatus() { return status; }
    public Integer getTotalPrice() { return totalPrice; }
    public String getPgOrderId() { return pgOrderId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getHoldExpiresAt() { return holdExpiresAt; }
    public OffsetDateTime getPayingAt() { return payingAt; }
    public OffsetDateTime getCheckedInAt() { return checkedInAt; }
    public OffsetDateTime getWithdrawnAt() { return withdrawnAt; }
}
