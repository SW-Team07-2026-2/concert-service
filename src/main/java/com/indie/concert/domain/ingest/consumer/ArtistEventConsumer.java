package com.indie.concert.domain.ingest.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.concert.global.event.EventEnvelope;
import com.indie.concert.domain.ingest.service.IngestService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Catalog artist.updated → 이 아티스트 공연의 artist_name · artist_genre 갱신 (02 ⑥ · 10/24 — 07 §5 #8 범위 조정 철회).
 * 파티션 키가 artistId 라 같은 아티스트의 변경은 순서대로 옵니다. 멱등 — 같은 값으로 덮어쓰기만 합니다.
 */
@Component
public class ArtistEventConsumer {

    private final IngestService ingestService;
    private final ObjectMapper objectMapper;

    public ArtistEventConsumer(IngestService ingestService, ObjectMapper objectMapper) {
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "artist.updated")
    public void onArtistUpdated(ConsumerRecord<String, String> record, Acknowledgment ack) throws JsonProcessingException {
        EventEnvelope e = EventEnvelope.parse(objectMapper, record.value());
        long artistId = e.requiredLong("artistId");
        String name = e.text("name");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("event " + e.eventId() + " without name");
        ingestService.applyArtist(artistId, name, e.text("genre"));
        ack.acknowledge();
    }
}
