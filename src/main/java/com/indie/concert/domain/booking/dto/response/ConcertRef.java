package com.indie.concert.domain.booking.dto.response;

import java.time.OffsetDateTime;

public record ConcertRef(long id, String title, OffsetDateTime startsAt, String venueName) {}
