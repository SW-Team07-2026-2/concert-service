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
 * Payment 이벤트 — Saga ① 의 결과 반영. orderType=TICKET 만 (GOODS 는 Catalog 몫).
 * <pre>
 * payment.completed  → 예매 CONFIRMED · 좌석 SOLD · booking.completed
 * payment.failed     → 무시 — 거절돼도 선점은 유지하고 새 결제로 다시 합니다 (11/04 80B). 응답을 못 받은 거절은 1분 뒤 정리가 처리
 * payment.cancelled  → 예매 CANCELLED · 좌석 반환 (Payment P4 는 TICKET 을 받지 않으므로 N13 · 만료가 부른 취소)
 * </pre>
 * 멱등 — 상태 전이가 조건부 UPDATE 라 같은 이벤트가 두 번 와도 한 번만 반영됩니다 (별도 처리 테이블 불필요).
 */
@Component
public class PaymentEventConsumer {

    private final BookingService bookingService;
    private final ObjectMapper objectMapper;

    public PaymentEventConsumer(BookingService bookingService, ObjectMapper objectMapper) {
        this.bookingService = bookingService;
        this.objectMapper = objectMapper;
    }

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

    /** 형식이 틀리면 IllegalArgumentException → 재시도 없이 DLT */
    private static long bookingId(String orderRef) {
        if (orderRef == null) throw new IllegalArgumentException("payment event without orderRef");
        return Long.parseLong(orderRef);
    }
}
