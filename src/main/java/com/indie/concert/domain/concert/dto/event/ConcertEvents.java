package com.indie.concert.domain.concert.dto.event;

import com.indie.concert.domain.concert.dto.response.ArtistRef;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.ConcertNoticeEntity;
import com.indie.concert.domain.concert.entity.VenueEntity;
import com.indie.concert.global.event.EventPublisher;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Concert 공연 등록, 라인업 추가와 변경 공지 -> Outbox -> Kafka -> Member 알림과 AI 연동.
 * 라인업은 순서를 유지하며 첫 아티스트를 헤드라이너로 전달합니다.
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

    public void noticePosted(ConcertEntity c, VenueEntity venue, ConcertNoticeEntity notice, List<Long> artistIds,
                             List<Long> bookerUserIds) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("concertId", c.getId());
        p.put("noticeId", notice.getId());
        p.put("kind", notice.getKind().name());
        p.put("noticeTitle", notice.getTitle());
        p.put("title", c.getTitle());
        p.put("startsAt", c.getStartsAt().toString());
        p.put("venueName", venue.getName());
        p.put("artistIds", artistIds);
        p.put("bookerUserIds", bookerUserIds);
        publisher.publish("concert.notice_posted", p);
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
