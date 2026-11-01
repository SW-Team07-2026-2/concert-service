package com.indie.concert.global.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 이벤트 봉투 { eventId, type, version, occurredAt, payload } (08_기능상세 §0.7).
 * 모르는 필드는 무시합니다. payload 래퍼가 없는 평평한 형식(payment.completed 예시)도 받아 줍니다.
 * 필수 값이 없으면 IllegalArgumentException → 재시도 없이 DLT.
 */
public record EventEnvelope(UUID eventId, OffsetDateTime occurredAt, JsonNode payload) {

    public static EventEnvelope parse(ObjectMapper objectMapper, String json) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(json);
        if (root == null || !root.hasNonNull("eventId")) {
            throw new IllegalArgumentException("event without eventId");
        }
        JsonNode payload = root.hasNonNull("payload") ? root.get("payload") : root;
        String occurred = root.hasNonNull("occurredAt") ? root.get("occurredAt").asText()
                : payload.hasNonNull("occurredAt") ? payload.get("occurredAt").asText() : null;
        return new EventEnvelope(UUID.fromString(root.get("eventId").asText()),
                occurred == null ? OffsetDateTime.now() : OffsetDateTime.parse(occurred), payload);
    }

    public Long longField(String name) {
        return payload.hasNonNull(name) ? payload.get(name).asLong() : null;
    }

    public long requiredLong(String name) {
        Long value = longField(name);
        if (value == null) throw new IllegalArgumentException("event " + eventId + " without " + name);
        return value;
    }

    public String text(String name) {
        return payload.hasNonNull(name) ? payload.get(name).asText() : null;
    }
}
