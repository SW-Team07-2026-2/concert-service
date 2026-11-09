package com.indie.concert.domain.queue.service;

import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.queue.dto.response.QueueStatus;
import com.indie.concert.domain.queue.repository.QueueEntryRepository;
import com.indie.concert.domain.queue.repository.QueueStore;
import com.indie.concert.global.common.exception.GeneralException;
import com.indie.concert.global.config.ConcertProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueueServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final OffsetDateTime AT = NOW.atOffset(ZoneOffset.UTC);

    private final QueueStore store = mock(QueueStore.class);
    private final QueueEntryRepository entries = mock(QueueEntryRepository.class);
    private final ConcertRepository concerts = mock(ConcertRepository.class);
    private final QueueService service = new QueueService(store, entries, concerts,
            new ConcertProperties(null, null, null, "Asia/Seoul", null,
                    new ConcertProperties.Queue(20, 1000, 200, 5000, 1000, 15, 10, 2000, 3000, 5000),
                    null, null, null, null, null),
            new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC));

    /** ★ 대기열 Redis 가 비워져도 입장한 사람은 같은 토큰을 남은 시간만큼 다시 받습니다 — 줄을 다시 서지 않음 (10/24) */
    @Test
    void rebuildRestoresEntryTokensWithRemainingTtl() {
        ConcertEntity concert = mock(ConcertEntity.class);
        when(concert.getId()).thenReturn(1L);
        when(concert.isQueueEnabled()).thenReturn(true);
        when(concert.getStartsAt()).thenReturn(AT.plusDays(30));
        when(concerts.findById(1L)).thenReturn(Optional.of(concert));
        when(store.isReady(1L)).thenReturn(false);
        when(store.lockRebuild(1L)).thenReturn("lock");
        when(entries.findWaitingAfter(eq(1L), anyLong(), anyInt())).thenReturn(List.of());
        when(entries.findAdmittedSince(1L, AT.minusMinutes(10))).thenReturn(List.of(
                new QueueEntryRepository.ActiveEntry(5, "tok-5", AT.minusMinutes(3))));
        when(store.rank(1L, 5L)).thenReturn(OptionalLong.empty());
        when(store.entry(1L, 5L)).thenReturn(Optional.of(new QueueStore.Entry("tok-5", 420)));

        QueueStatus status = service.status(1L, 5L);

        assertThat(status.state()).isEqualTo("ADMITTED");
        assertThat(status.entryToken()).isEqualTo("tok-5");
        InOrder order = inOrder(store);
        order.verify(store).restoreEntries(1L, List.of(new QueueStore.EntryGrant(5, "tok-5", 7 * 60_000)));
        order.verify(store).markReady(eq(1L), anyLong(), any());                     // 토큰을 먼저 — 준비되자마자 조회돼도 있게
    }

    /** 입장 토큰 — 없음 · 입장 기록 없음(10분 지남) · 다른 토큰은 코드가 따로 (모두 403) */
    @Test
    void entryChecksHaveOwnCodes() {
        ConcertEntity concert = mock(ConcertEntity.class);
        when(concert.getId()).thenReturn(1L);
        when(concert.isQueueEnabled()).thenReturn(true);
        when(concert.saleStatus(any())).thenReturn(ConcertEntity.SaleStatus.ON_SALE);
        when(store.entry(1L, 5L)).thenReturn(Optional.empty());
        when(store.entry(1L, 6L)).thenReturn(Optional.of(new QueueStore.Entry("tok-6", 300)));

        assertEntryRejected(concert, 6L, null, "ENTRY_TOKEN_REQUIRED");
        assertEntryRejected(concert, 5L, "tok-5", "QUEUE_NOT_ADMITTED");
        assertEntryRejected(concert, 6L, "nope", "ENTRY_TOKEN_INVALID");
        assertThat(service.requireEntry(concert, 6L, " tok-6 ")).hasValue(300);
    }

    private void assertEntryRejected(ConcertEntity concert, long userId, String token, String code) {
        assertThatThrownBy(() -> service.requireEntry(concert, userId, token))
                .isInstanceOfSatisfying(GeneralException.class, e -> {
                    assertThat(e.getErrorCode().getStatus().value()).isEqualTo(403);
                    assertThat(e.getErrorCode().getCode()).isEqualTo(code);
                });
    }
}
