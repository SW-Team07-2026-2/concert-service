package com.indie.concert.domain.booking.dto.response;

import java.time.OffsetDateTime;

/**
 * bookingId = 선점 id (holdId) — 이후 단계는 이것 하나로 진행합니다 (11/04 77A).
 * expiresAt = 화면 타이머 마감 (선점 뒤 8분) — 이 안에 결제 승인(N16)해야 합니다. 서버는 60초 더 지켜 줍니다. 그동안 좌석은 나만 씁니다.
 * expiresIn = expiresAt 까지 남은 초 (DB 시계 기준) — 프론트 타이머는 이 값으로 (기기 시계 오차 무관).
 * pgOrderId — 토스 SDK requestPayment 의 orderId
 */
public record BookingCreated(long bookingId, int amount, String pgOrderId, String status, OffsetDateTime expiresAt,
                             long expiresIn) {}
