package com.indie.concert.domain.queue.service;

import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.queue.dto.response.QueueStatus;
import com.indie.concert.domain.queue.repository.QueueEntryRepository;
import com.indie.concert.domain.queue.repository.QueueStore;
import com.indie.concert.domain.seat.repository.ConcertSeatRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueueServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final OffsetDateTime AT = NOW.atOffset(ZoneOffset.UTC);

    private final QueueStore store = mock(QueueStore.class);
    private final QueueEntryRepository entries = mock(QueueEntryRepository.class);
    private final ConcertRepository concerts = mock(ConcertRepository.class);
    private final ConcertSeatRepository seats = mock(ConcertSeatRepository.class);
    private final QueueService service = new QueueService(store, entries, concerts, seats,
            new ConcertProperties(null, null, null, "Asia/Seoul", null,
                    new ConcertProperties.Queue(20, 1000, 200, 5000, 1000, 15, 10, 2000, 15000, 3000, 5000),
                    null, null, null, null, null),
            new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC));

    /** ★ 대기열 Redis 가 비워져도 입장한 사람은 같은 토큰을 남은 시간만큼 다시 받습니다 — 줄을 다시 서지 않음 (10/24) */
    @Test
    void rebuildRestoresEntryTokensWithRemainingTtl() {
        ConcertEntity concert = mock(ConcertEntity.class);
        when(concert.getId()).thenReturn(1L);
        when(concert.isQueueEnabled()).thenReturn(true);
        when(concert.getStartsAt()).thenReturn(AT.plusDays(30));
        when(concert.getLastStartsAt()).thenReturn(AT.plusDays(30));
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

    /** ✏️ 11/25 순번이 멀수록 조회 간격이 깁니다 — 예상 대기의 1/3 · 2초 ~ 15초 (1초에 20명 입장) */
    @Test
    void pollIntervalGrowsWithRank() {
        onSaleConcert();
        seatCounts(100, 100, 50);
        when(store.isReady(1L)).thenReturn(true);
        when(store.size(1L)).thenReturn(5_000L);

        assertThat(pollAfterAtRank(10)).isEqualTo(2_000);       // 1초 뒤 입장 → 가장 짧게
        assertThat(pollAfterAtRank(300)).isEqualTo(5_000);      // 15초 → 5초
        assertThat(pollAfterAtRank(5_000)).isEqualTo(15_000);   // 250초 → 상한 15초
    }

    /** ✏️ 11/25 모든 좌석이 결제 완료 — 진입은 410 SOLD_OUT · 기다리던 사람은 SOLD_OUT 을 받고 조회를 멈춤 · 입장 처리도 멈춤 */
    @Test
    void soldOutClosesQueue() {
        ConcertEntity concert = onSaleConcert();
        seatCounts(100, 0, 0);
        when(store.isReady(1L)).thenReturn(true);
        when(store.rank(1L, 5L)).thenReturn(OptionalLong.of(3));
        when(store.lead(eq(1L), any())).thenReturn(true);

        assertThatThrownBy(() -> service.enter(1L, 6L))
                .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getErrorCode().getCode()).isEqualTo("SOLD_OUT"));
        assertThat(service.status(1L, 5L).state()).isEqualTo("SOLD_OUT");
        service.admit(concert);
        verify(store, never()).admit(anyLong(), any(), anyInt());
    }

    /** ✏️ 11/25 남은 좌석이 전부 선점 · 결제 중이면 입장만 멈춤 — 줄은 그대로 · 가장 긴 간격으로 조회 (선점이 풀리면 다시 입장) */
    @Test
    void admissionPausesWhileAllSeatsHeld() {
        ConcertEntity concert = onSaleConcert();
        seatCounts(100, 40, 0);
        when(store.isReady(1L)).thenReturn(true);
        when(store.lead(eq(1L), any())).thenReturn(true);
        when(store.size(1L)).thenReturn(50L);

        assertThat(pollAfterAtRank(2)).isEqualTo(15_000);
        service.admit(concert);
        verify(store, never()).admit(anyLong(), any(), anyInt());
    }

    /** ✏️ 11/25 좌석을 아직 안 올린 공연(0석)은 매진으로 보지 않고 예전처럼 입장시킵니다 */
    @Test
    void concertWithoutSeatsKeepsAdmitting() {
        ConcertEntity concert = onSaleConcert();
        seatCounts(0, 0, 0);
        when(store.isReady(1L)).thenReturn(true);
        when(store.lead(eq(1L), any())).thenReturn(true);
        when(store.admit(eq(1L), any(), anyInt())).thenReturn(new QueueStore.Admission(List.of(), List.of()));

        service.admit(concert);
        verify(store).admit(eq(1L), any(), anyInt());
    }

    /** ✏️ 11/25 순번 조회가 몰려도 공연 · 남은 좌석은 3초에 한 번만 DB 에서 읽습니다 */
    @Test
    void statusReadsConcertAndSeatsOncePerTtl() {
        onSaleConcert();
        seatCounts(100, 100, 50);
        when(store.isReady(1L)).thenReturn(true);
        when(store.size(1L)).thenReturn(10L);

        for (int i = 0; i < 100; i++) pollAfterAtRank(5);

        verify(concerts, times(1)).findById(1L);
        verify(seats, times(1)).countSeats(1L);
    }

    private long pollAfterAtRank(long rank) {
        when(store.rank(1L, 5L)).thenReturn(OptionalLong.of(rank - 1));
        QueueStatus status = service.status(1L, 5L);
        assertThat(status.state()).isEqualTo("WAITING");
        return status.pollAfterMs();
    }

    private ConcertEntity onSaleConcert() {
        ConcertEntity concert = mock(ConcertEntity.class);
        when(concert.getId()).thenReturn(1L);
        when(concert.isQueueEnabled()).thenReturn(true);
        when(concert.getStartsAt()).thenReturn(AT.plusDays(30));
        when(concert.getLastStartsAt()).thenReturn(AT.plusDays(30));
        when(concert.getBookingOpenAt()).thenReturn(AT.minusMinutes(1));
        when(concerts.findById(1L)).thenReturn(Optional.of(concert));
        return concert;
    }

    private void seatCounts(long total, long unsold, long available) {
        when(seats.countSeats(1L)).thenReturn(new ConcertSeatRepository.SeatCounts() {
            public long getTotal() { return total; }
            public long getUnsold() { return unsold; }
            public long getAvailable() { return available; }
        });
    }

    private void assertEntryRejected(ConcertEntity concert, long userId, String token, String code) {
        assertThatThrownBy(() -> service.requireEntry(concert, userId, token))
                .isInstanceOfSatisfying(GeneralException.class, e -> {
                    assertThat(e.getErrorCode().getStatus().value()).isEqualTo(403);
                    assertThat(e.getErrorCode().getCode()).isEqualTo(code);
                });
    }
}
