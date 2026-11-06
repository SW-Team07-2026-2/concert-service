package com.indie.concert.domain.booking.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * N16 — status: CONFIRMED (승인 · 좌석 확정) · PENDING (거절 · 선점 유지 — pgOrderId 로 결제창을 다시) · EXPIRED (거절됐고 선점 마감도 지남)
 * paymentStatus: APPROVED · DECLINED · reason: 거절 사유 (토스 에러 코드)
 * pgOrderId — 거절 뒤 새 결제의 토스 orderId (11/04 80B · 같은 orderId 로는 다시 승인할 수 없음)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookingPaid(long bookingId, String status, String paymentStatus, String reason, String pgOrderId) {}
