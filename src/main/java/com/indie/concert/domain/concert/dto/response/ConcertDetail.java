package com.indie.concert.domain.concert.dto.response;

import java.time.OffsetDateTime;

public record ConcertDetail(long id, String title, String type, long artistId, String artistName, String artistGenre,
                            OffsetDateTime startsAt, Integer price, String saleStatus, OffsetDateTime bookingOpenAt,
                            boolean queueEnabled, VenueView venue) {}
