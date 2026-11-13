package com.indie.concert.domain.concert.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/** maxSeatsPerUser — 한 번에 예매할 수 있는 좌석 수 (1인 N매) */
public record ConcertDetail(long id, String title, String type, String genre, List<ArtistRef> artists,
                            OffsetDateTime startsAt, OffsetDateTime endsAt, Integer price, String saleStatus,
                            OffsetDateTime bookingOpenAt, boolean queueEnabled, int maxSeatsPerUser, VenueView venue) {}
