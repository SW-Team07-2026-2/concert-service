package com.indie.concert.domain.booking.dto.response;

import java.time.OffsetDateTime;

public record BookingSummary(long bookingId, String status, int totalPrice, OffsetDateTime createdAt,
                             long concertId, String concertTitle, OffsetDateTime startsAt) {}
