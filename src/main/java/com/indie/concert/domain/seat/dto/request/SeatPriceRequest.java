package com.indie.concert.domain.seat.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 공연 좌석 가격 — seatId 는 공연장 배치 좌석 id (seat.id) · 목록에 없는 좌석은 이 공연에서 팔지 않습니다 */
public record SeatPriceRequest(@NotEmpty @Size(max = 20000) List<@NotNull @Valid SeatPrice> seats) {

    public record SeatPrice(@NotNull @Positive Long seatId, @NotNull @Positive Integer price) {}
}
