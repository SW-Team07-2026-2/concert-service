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
 * Member -> Kafka user.withdrawn -> Concert 탈퇴 회원 예매 정리.
 * 정리 표시를 먼저 저장하고 남은 환불이나 만료 처리는 배치가 재시도합니다.
 */
@Component
public class UserEventConsumer {

    private final BookingService bookingService;
    private final ObjectMapper objectMapper;

    public UserEventConsumer(BookingService bookingService, ObjectMapper objectMapper) {
        this.bookingService = bookingService;
        this.objectMapper = objectMapper;
    }

    /**
     * 회원 탈퇴 이벤트를 받아 예매 환불 또는 만료를 처리합니다.
     *
     * @param record Kafka에서 수신한 이벤트 레코드
     * @param ack 처리 완료 후 오프셋을 커밋할 승인 객체
     */
    @KafkaListener(topics = "user.withdrawn")
    public void onUserWithdrawn(ConsumerRecord<String, String> record, Acknowledgment ack) throws JsonProcessingException {
        EventEnvelope e = EventEnvelope.parse(objectMapper, record.value());
        bookingService.releaseWithdrawn(e.requiredLong("userId"));
        ack.acknowledge();
    }
}
