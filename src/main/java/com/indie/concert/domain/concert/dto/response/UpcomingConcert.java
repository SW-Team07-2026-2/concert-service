package com.indie.concert.domain.concert.dto.response;

import java.time.OffsetDateTime;

public record UpcomingConcert(long id, String title, String type, long artistId, String artistName, OffsetDateTime startsAt,
                       String saleStatus) {}
