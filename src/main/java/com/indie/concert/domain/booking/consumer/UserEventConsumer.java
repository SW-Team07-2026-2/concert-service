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
 * Member user.withdrawn → 탈퇴 회원의 결제 전 예매(선점)를 정리해 좌석을 돌려놓습니다. 확정 예매는 남깁니다.
 * 멱등 — 정리할 예매가 없으면 아무것도 하지 않습니다. Payment 불통이면 예외로 재시도합니다 (KafkaConfig 의 재시도 → DLT).
 * 재시도가 끝나 DLT 로 가도 선점은 서버 마감(최대 약 10분) 뒤 만료 정리가 돌려놓습니다 — 이 컨슈머는 좌석을 더 빨리 풀 뿐입니다.
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
