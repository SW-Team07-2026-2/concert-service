package com.indie.concert.domain.layout.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** 좌석 하나 — row · number 는 1 부터 · x · y 는 캔버스 기준 0~1 · angle 은 무대 기준 기울기(도) */
public record SeatPoint(@Size(max = 64) String seatId,
                        @NotNull @Positive Integer row,
                        @NotNull @Positive Integer number,
                        @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal x,
                        @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal y,
                        @NotNull @DecimalMin("-360") @DecimalMax("360") BigDecimal angle) {}
