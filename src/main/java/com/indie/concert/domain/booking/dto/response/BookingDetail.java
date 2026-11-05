package com.indie.concert.domain.booking.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/** expiresAt · expiresIn(남은 초) — PENDING 일 때만. 결제창에서 돌아왔는데 타이머 값이 없으면 이것으로 복구 */
public record BookingDetail(long bookingId, String status, int totalPrice, String pgOrderId, OffsetDateTime createdAt,
                            OffsetDateTime expiresAt, Long expiresIn, ConcertRef concert, List<SeatRef> seats) {}
