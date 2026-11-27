package com.indie.concert.domain.concert.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * maxSeatsPerUser - 한 번에 예매할 수 있는 좌석 수 (1인 N매, 회차마다). description, terms, requirements 는 마크다운, 없으면 null.
 * startsAt, lastStartsAt = 첫, 마지막 회차 시작, sessions = 회차 (시작 순, 하나 이상), saleStatus 는 마지막 회차 기준
 */
public record ConcertDetail(long id, String title, String type, String genre, List<ArtistRef> artists,
                            OffsetDateTime startsAt, OffsetDateTime lastStartsAt, OffsetDateTime endsAt, Integer price,
                            String saleStatus, OffsetDateTime bookingOpenAt, boolean queueEnabled, int maxSeatsPerUser,
                            VenueView venue, String description, String terms, String requirements, String posterUrl,
                            List<String> imageUrls, List<SessionView> sessions) {

    /** 캐시에 둔 상세의 판매 상태만 요청 시각 기준으로 바꿉니다 (ConcertService.getPublic) */
    public ConcertDetail withSaleStatus(String saleStatus, List<SessionView> sessions) {
        return new ConcertDetail(id, title, type, genre, artists, startsAt, lastStartsAt, endsAt, price, saleStatus, bookingOpenAt,
                queueEnabled, maxSeatsPerUser, venue, description, terms, requirements, posterUrl, imageUrls, sessions);
    }
}
