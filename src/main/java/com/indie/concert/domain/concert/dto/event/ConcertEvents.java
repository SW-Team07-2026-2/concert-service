package com.indie.concert.domain.concert.dto.event;

import com.indie.concert.domain.concert.dto.response.ArtistRef;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.VenueEntity;
import com.indie.concert.global.event.EventPublisher;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공연 이벤트 — 관리자 등록 · 라인업 수정 커밋에서 (outbox).
 * <pre>
 * concert.created         등록         Member: 라인업 전체 팔로워에게 알림 · AI: 인덱싱
 * concert.lineup_updated  라인업 추가   Member: 새로 추가된 아티스트(addedArtistIds)의 팔로워에게 알림
 * </pre>
 * artistIds 는 라인업 순서 (첫 번째 = 헤드라이너). artistId · artistName 은 헤드라이너 — artistIds 이전 구독자용으로 남깁니다.
 * 🔴 그 밖의 공연 정보 수정은 이벤트가 없습니다.
 */
@Component
public class ConcertEvents {

    private final EventPublisher publisher;

    public ConcertEvents(EventPublisher publisher) {
        this.publisher = publisher;
    }

    public void concertCreated(ConcertEntity c, VenueEntity venue, List<ArtistRef> lineup) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("concertId", c.getId());
        p.put("artistId", lineup.get(0).id());
        p.put("artistName", lineup.get(0).name());
        p.put("artistIds", lineup.stream().map(ArtistRef::id).toList());
        p.put("title", c.getTitle());
        p.put("startsAt", c.getStartsAt().toString());
        p.put("venueName", venue.getName());
        p.put("region", venue.getRegion().name());
        p.put("type", c.getType().name());
        p.put("genre", c.getGenre());
        publisher.publish("concert.created", p);
    }

    public void lineupUpdated(ConcertEntity c, VenueEntity venue, List<Long> artistIds, List<Long> addedArtistIds) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("concertId", c.getId());
        p.put("title", c.getTitle());
        p.put("startsAt", c.getStartsAt().toString());
        p.put("venueName", venue.getName());
        p.put("artistIds", artistIds);
        p.put("addedArtistIds", addedArtistIds);
        publisher.publish("concert.lineup_updated", p);
    }
}
