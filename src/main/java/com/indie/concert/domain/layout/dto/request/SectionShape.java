package com.indie.concert.domain.layout.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * shape — 꼭짓점 [[x, y], ...] (캔버스 기준 0~1 · 3개 이상) · angle — 무대 기준 기울기(도).
 * rows · seatCount 는 확인용 — seatCount 가 오면 seats 개수와 같아야 합니다.
 */
public record SectionShape(@NotBlank @Size(max = 16) String name,
                           @NotNull @Size(min = 3, max = 500)
                           List<@NotNull @Size(min = 2, max = 2) List<@NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal>> shape,
                           @NotNull @DecimalMin("-360") @DecimalMax("360") BigDecimal angle,
                           @Positive Integer rows,
                           @Positive Integer seatCount) {}
