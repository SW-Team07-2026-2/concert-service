package com.indie.concert.domain.booking.dto.response;

import java.time.OffsetDateTime;

/**
 * expiresAt = 좌석 선택 만료 (고른 뒤 3분) — 이 시각 안에 결제 승인(N16)해야 합니다. 그 전에 다른 사람이 먼저 결제하면 409.
 * pgOrderId — 토스 SDK requestPayment 의 orderId
 */
public record BookingCreated(long bookingId, int amount, String pgOrderId, String status, OffsetDateTime expiresAt) {}
