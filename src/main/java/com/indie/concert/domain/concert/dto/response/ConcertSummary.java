package com.indie.concert.domain.concert.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * genre — 단독 공연은 아티스트 장르 · 페스티벌은 관리자가 정한 장르.
 * posterUrl — 목록 카드 포스터 (상세와 같은 값 · 없으면 null · 11/19 목록 · 캘린더에도 넣음)
 */
public record ConcertSummary(long id, String title, String type, String genre, List<ArtistRef> artists,
                             OffsetDateTime startsAt, Integer price, String saleStatus, VenueRef venue, String posterUrl) {}
