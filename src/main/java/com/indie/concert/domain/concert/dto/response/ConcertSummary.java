package com.indie.concert.domain.concert.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/** genre — 단독 공연은 아티스트 장르 · 페스티벌은 관리자가 정한 장르 */
public record ConcertSummary(long id, String title, String type, String genre, List<ArtistRef> artists,
                             OffsetDateTime startsAt, Integer price, String saleStatus, VenueRef venue) {}
