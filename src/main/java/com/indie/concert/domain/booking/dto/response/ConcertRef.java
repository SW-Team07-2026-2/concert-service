package com.indie.concert.domain.booking.dto.response;

import java.time.OffsetDateTime;

/** startsAt = 예매한(검표 화면이면 지금 입장이 열린) 회차의 시작, sessionId = 그 회차 */
public record ConcertRef(long id, String title, OffsetDateTime startsAt, String venueName, long sessionId) {}
