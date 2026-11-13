package com.indie.concert.domain.concert.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record UpcomingConcert(long id, String title, String type, List<ArtistRef> artists, OffsetDateTime startsAt,
                              String saleStatus) {}
