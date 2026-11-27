package com.indie.concert.domain.booking.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.concert.domain.booking.service.BookingService;
import com.indie.concert.global.event.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Member user.withdrawn → 탈퇴 회원의 예매 정리 (11/27 TOS v3 — 선점 반환 · 회차 24시간 전보다 앞이면 환불 · 그 뒤면 환불 없이 만료).
 * 멱등 — 정리 표시(withdrawn_at)가 된 예매만 다룹니다. 표시를 먼저 커밋하므로 Payment 불통이어도 예외를 내지 않고,
 * 남은 건은 BookingExpiryJob 이 30초마다 이어서 합니다. 표시(DB)가 실패할 때만 예외 → KafkaConfig 의 재시도 → DLT.
 */
@Component
public class UserEventConsumer {

    private final BookingService bookingService;
    private final ObjectMapper objectMapper;

    public UserEventConsumer(BookingService bookingService, ObjectMapper objectMapper) {
        this.bookingService = bookingService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "user.withdrawn")
    public void onUserWithdrawn(ConsumerRecord<String, String> record, Acknowledgment ack) throws JsonProcessingException {
        EventEnvelope e = EventEnvelope.parse(objectMapper, record.value());
        bookingService.releaseWithdrawn(e.requiredLong("userId"));
        ack.acknowledge();
    }
}
