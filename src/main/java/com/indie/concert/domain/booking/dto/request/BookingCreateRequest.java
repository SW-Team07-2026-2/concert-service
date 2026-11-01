package com.indie.concert.domain.booking.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record BookingCreateRequest(@NotNull @Positive Long seatId) {}
