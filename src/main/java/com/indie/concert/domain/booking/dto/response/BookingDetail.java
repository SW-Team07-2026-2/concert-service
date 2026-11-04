package com.indie.concert.domain.booking.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record BookingDetail(long bookingId, String status, int totalPrice, String pgOrderId, OffsetDateTime createdAt,
                            OffsetDateTime expiresAt, ConcertRef concert, List<SeatRef> seats) {}
