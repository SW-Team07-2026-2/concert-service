package com.indie.concert.domain.booking.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/** N16 — status: CONFIRMED (승인 · 좌석 확정) · CANCELLED (거절 · 다시 고르기) / paymentStatus: APPROVED · DECLINED · reason: 거절 사유 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookingPaid(long bookingId, String status, String paymentStatus, String reason) {}
