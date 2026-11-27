package com.indie.concert.global.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 회원 행동 메트릭 -> user.metric (Member 가 user_metric 에 저장, member-service docs/v2_member_service_re.md 6.5).
 * Concert 는 공연 검색어만 남깁니다 - 예매는 Member 가 booking.completed 로 직접 남깁니다.
 * 아웃박스에 쓰려고 트랜잭션을 새로 엽니다. metricId 는 여기서 정합니다 - 릴레이가 다시 보내도 Member 가 한 번만 저장합니다.
 */
@Component
public class UserMetrics {

    public static final String TOPIC = "user.metric";

    /** 같은 회원, 검색어는 이 구간에 한 번만 - 칩만 바꿔 다시 부를 때 여러 번 세지 않게 (Catalog 와 같은 값) */
    static final Duration SEARCH_WINDOW = Duration.ofMinutes(10);

    private final EventPublisher publisher;
    private final TransactionTemplate tx;
    private final Clock clock;

    public UserMetrics(EventPublisher publisher, PlatformTransactionManager transactionManager, Clock clock) {
        this.publisher = publisher;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 공연 검색어 - 로그인했고 검색어가 있는 첫 페이지만 (다음 페이지, 칩만 고른 검색, 비로그인은 남기지 않음) */
    public void searched(Long userId, String q, int page) {
        if (userId == null || q == null || q.isBlank() || page != 0) {
            return;
        }
        String keyword = q.strip();
        long window = clock.millis() / SEARCH_WINDOW.toMillis();
        UUID id = UUID.nameUUIDFromBytes(("search:" + userId + ":CONCERT:" + keyword + ":" + window)
                .getBytes(StandardCharsets.UTF_8));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("userId", userId);
        p.put("metricId", id.toString());
        p.put("targetType", "CONCERT");
        p.put("targetId", null);
        p.put("metricType", "SEARCH");
        p.put("metricValue", 1L);
        p.put("properties", Map.of("keyword", keyword));
        tx.executeWithoutResult(status -> publisher.publish(TOPIC, p));
    }
}
