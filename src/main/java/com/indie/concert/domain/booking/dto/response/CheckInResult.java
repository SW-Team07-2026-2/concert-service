package com.indie.concert.domain.booking.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/** 검표 성공 — 검표 화면이 좌석을 보여줍니다 */
public record CheckInResult(long bookingId, List<SeatRef> seats, OffsetDateTime checkedInAt) {}
