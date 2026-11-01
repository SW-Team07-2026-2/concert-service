package com.indie.concert.domain.seat.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 행 번호는 1 부터 문자열로 ("1" · "2" …) */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SeatSectionRequest(@NotBlank @Size(max = 16) String section,
                          @NotNull @Positive @Max(200) Integer rows,
                          @NotNull @Positive @Max(200) Integer seatsPerRow,
                          @NotNull @Positive Integer price) {}
