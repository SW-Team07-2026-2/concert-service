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
 * Payment -> Kafka 결제 이벤트 -> Concert 티켓 예매와 좌석 상태 반영.
 * 승인은 확정, 취소는 좌석 반환으로 처리하며 결제 거절 시에는 선점을 유지합니다.
 */
@Component
public class PaymentEventConsumer {

    private final BookingService bookingService;
    private final ObjectMapper objectMapper;

    public PaymentEventConsumer(BookingService bookingService, ObjectMapper objectMapper) {
        this.bookingService = bookingService;
        this.objectMapper = objectMapper;
    }

    /**
     * 티켓 결제 이벤트를 받아 예매와 좌석 상태에 반영합니다.
     *
     * @param record Kafka에서 수신한 이벤트 레코드
     * @param ack 처리 완료 후 오프셋을 커밋할 승인 객체
     */
    @KafkaListener(topics = {"payment.completed", "payment.failed", "payment.cancelled"})
    public void onPayment(ConsumerRecord<String, String> record, Acknowledgment ack) throws JsonProcessingException {
        EventEnvelope e = EventEnvelope.parse(objectMapper, record.value());
        if ("TICKET".equals(e.text("orderType"))) {
            long bookingId = bookingId(e.text("orderRef"));
            if ("payment.completed".equals(record.topic())) {
                bookingService.confirm(bookingId);
            } else if ("payment.cancelled".equals(record.topic())) {
                bookingService.cancelLocally(bookingId);
            }
        }
        ack.acknowledge();
    }

    /** 형식이 틀리면 IllegalArgumentException -> 재시도 없이 DLT */
    private static long bookingId(String orderRef) {
        if (orderRef == null) throw new IllegalArgumentException("payment event without orderRef");
        return Long.parseLong(orderRef);
    }
}
