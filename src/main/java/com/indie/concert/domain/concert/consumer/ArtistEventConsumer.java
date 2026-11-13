package com.indie.concert.domain.concert.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.concert.domain.concert.service.ArtistService;
import com.indie.concert.global.event.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Catalog artist.updated → artist 복제 테이블 · 그 아티스트 단독 공연의 장르.
 * Catalog 는 생성 · 변경마다 전체 값(name · genre)을 보냅니다 — genre 가 null 이면 장르 없음입니다.
 * 파티션 키가 artistId 라 같은 아티스트의 변경은 순서대로 오고, 재전달된 옛 이벤트는 occurredAt 비교로 무시합니다.
 */
@Component
public class ArtistEventConsumer {

    private final ArtistService artistService;
    private final ObjectMapper objectMapper;

    public ArtistEventConsumer(ArtistService artistService, ObjectMapper objectMapper) {
        this.artistService = artistService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "artist.updated")
    public void onArtistUpdated(ConsumerRecord<String, String> record, Acknowledgment ack) throws JsonProcessingException {
        EventEnvelope e = EventEnvelope.parse(objectMapper, record.value());
        long artistId = e.requiredLong("artistId");
        String name = e.text("name");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("event " + e.eventId() + " without name");
        artistService.apply(artistId, name, e.text("genre"), e.occurredAt());
        ack.acknowledge();
    }
}
