package com.indie.concert.global.event;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 11/24 공연 검색어 메트릭 — 남기는 조건과 10분 구간 안의 같은 검색어는 같은 metricId */
class UserMetricsTest {

    private final EventPublisher publisher = mock(EventPublisher.class);

    @Test
    @SuppressWarnings("unchecked")
    void searchedOnlyLoggedInFirstPageWithKeywordOncePerWindow() {
        at("2026-11-24T05:00:00Z").searched(null, "새소년", 0);
        at("2026-11-24T05:00:00Z").searched(42L, " ", 0);
        at("2026-11-24T05:00:00Z").searched(42L, "새소년", 1);
        verify(publisher, never()).publish(anyString(), any());

        at("2026-11-24T05:00:00Z").searched(42L, " 새소년 ", 0);
        at("2026-11-24T05:09:59Z").searched(42L, "새소년", 0);
        at("2026-11-24T05:10:00Z").searched(42L, "새소년", 0);

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(publisher, times(3)).publish(anyString(), payload.capture());
        assertThat(payload.getValue()).containsEntry("userId", 42L).containsEntry("targetType", "CONCERT")
                .containsEntry("targetId", null).containsEntry("metricType", "SEARCH")
                .containsEntry("properties", Map.of("keyword", "새소년"));
        List<Object> ids = payload.getAllValues().stream().map(p -> p.get("metricId")).toList();
        assertThat(ids.get(1)).isEqualTo(ids.get(0));
        assertThat(ids.get(2)).isNotEqualTo(ids.get(0));
    }

    private UserMetrics at(String instant) {
        return new UserMetrics(publisher, mock(PlatformTransactionManager.class),
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }
}
