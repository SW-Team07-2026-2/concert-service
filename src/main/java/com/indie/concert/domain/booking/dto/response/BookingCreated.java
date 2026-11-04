package com.indie.concert.domain.booking.dto.response;

import java.time.OffsetDateTime;

/**
 * expiresAt = 화면 타이머 마감 (고른 뒤 5분) — 이 안에 결제 승인(N16)해야 합니다. 서버는 60초 더 받습니다. 그 전에 다른 사람이 먼저 결제하면 409.
 * expiresIn = expiresAt 까지 남은 초 — 프론트 타이머는 이 값으로 (기기 시계 오차 무관).
 * pgOrderId — 토스 SDK requestPayment 의 orderId
 */
public record BookingCreated(long bookingId, int amount, String pgOrderId, String status, OffsetDateTime expiresAt,
                             long expiresIn) {}
