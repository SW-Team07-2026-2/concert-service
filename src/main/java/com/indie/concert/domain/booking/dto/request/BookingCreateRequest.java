package com.indie.concert.domain.booking.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** seatIds — 좌석 화면의 좌석 id (공연 좌석). 공연의 maxSeatsPerUser 개까지 (넘으면 409 SEAT_LIMIT_EXCEEDED) */
public record BookingCreateRequest(@NotEmpty @Size(max = 10) List<@NotNull @Positive Long> seatIds) {}
