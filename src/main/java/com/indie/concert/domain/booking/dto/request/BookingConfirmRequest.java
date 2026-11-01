package com.indie.concert.domain.booking.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** N16 — paymentKey 는 토스 successUrl 의 값 · amount 는 N10 이 준 값 그대로. Payment 가 준비 금액과 대조합니다 (위조하면 400 AMOUNT_MISMATCH) */
public record BookingConfirmRequest(@NotBlank @Size(max = 200) String paymentKey, @NotNull @Positive Integer amount) {}
