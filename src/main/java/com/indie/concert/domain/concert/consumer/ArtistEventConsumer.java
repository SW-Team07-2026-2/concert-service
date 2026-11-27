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
 * Catalog -> Kafka artist.updated -> Concert 아티스트 사본과 단독 공연 장르 갱신.
 * 이벤트 시각을 비교해 이전 이벤트가 새 값을 덮어쓰지 않도록 합니다.
 */
@Component
public class ArtistEventConsumer {

    private final ArtistService artistService;
    private final ObjectMapper objectMapper;

    public ArtistEventConsumer(ArtistService artistService, ObjectMapper objectMapper) {
        this.artistService = artistService;
        this.objectMapper = objectMapper;
    }

    /**
     * 아티스트 변경 이벤트를 복제 테이블과 공연 장르에 반영합니다.
     *
     * @param record Kafka에서 수신한 이벤트 레코드
     * @param ack 처리 완료 후 오프셋을 커밋할 승인 객체
     */
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
