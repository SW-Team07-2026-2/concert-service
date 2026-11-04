package com.indie.concert.domain.ingest.dto.event;

import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.VenueEntity;
import com.indie.concert.global.event.EventPublisher;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * concert.created — 공연 등록 커밋에서 (FR-CON-06 · 08 §11). Member 는 팔로워 피드(CONCERT_OPENED), AI 는 인덱싱.
 * 🔴 수정은 이벤트가 없습니다 (토픽이 없음) — 신규 등록만 나갑니다.
 */
@Component
public class ConcertEvents {

    private final EventPublisher publisher;

    public ConcertEvents(EventPublisher publisher) {
        this.publisher = publisher;
    }

    public void concertCreated(ConcertEntity c, VenueEntity venue) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("concertId", c.getId());
        p.put("artistId", c.getArtistId());
        p.put("artistName", c.getArtistName());
        p.put("title", c.getTitle());
        p.put("startsAt", c.getStartsAt().toString());
        p.put("venueName", venue.getName());
        p.put("region", venue.getRegion().name());
        p.put("type", c.getType().name());
        publisher.publish("concert.created", p);
    }
}
