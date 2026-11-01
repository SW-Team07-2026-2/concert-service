package com.indie.concert.global.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 컨슈머 실패 처리 (Member 명세 §12-2 와 동일) — 1초 간격 2회 재시도 → {topic}.DLT 로 보내고 오프셋 커밋 → 뒤 메시지는 계속 처리.
 * 🔴 무한 재시도 금지 — 형식이 깨진 메시지(poison pill)는 재시도로 낫지 않고 파티션을 멈춥니다. 그런 예외는 즉시 DLT.
 * Boot 가 이 빈을 기본 리스너 컨테이너에 연결합니다.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // 파티션 -1 = 브로커가 고름 (DLT 파티션 수가 원본과 달라도 발행 실패하지 않게)
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
        handler.addNotRetryableExceptions(JsonProcessingException.class, IllegalArgumentException.class);
        return handler;
    }
}
