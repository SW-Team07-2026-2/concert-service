package com.indie.concert.domain.seat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.layout.repository.LayoutRepository;
import com.indie.concert.domain.queue.exception.QueueErrorCode;
import com.indie.concert.domain.queue.service.QueueService;
import com.indie.concert.domain.seat.dto.response.SeatMap;
import com.indie.concert.domain.seat.repository.ConcertSeatRepository;
import com.indie.concert.domain.seat.repository.SeatCache;
import com.indie.concert.global.common.exception.GeneralException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 좌석 화면 — 배치 · 상태는 2초 캐시, 타이머(입장 토큰 남은 시간)는 요청마다 계산해 캐시에 섞이지 않습니다 */
class SeatServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    private final ConcertRepository concerts = mock(ConcertRepository.class);
    private final ConcertSeatRepository seats = mock(ConcertSeatRepository.class);
    private final LayoutRepository layout = mock(LayoutRepository.class);
    private final SeatCache cache = mock(SeatCache.class);
    private final QueueService queue = mock(QueueService.class);
    private final SeatService service = new SeatService(concerts, seats, layout, cache, queue, new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static final SeatMap CACHED = new SeatMap(1L, 4, 0, 0, null, null, List.of());

    /** 입장 토큰 남은 초를 그대로 — 프론트는 expiresIn 으로 타이머를 돌립니다 */
    @Test
    void entryTokenGivesTimerAtCallTime() {
        when(cache.get(1L)).thenReturn(Optional.of(CACHED));
        when(queue.entryLeft(1L, 3L, "tok")).thenReturn(301L);

        SeatMap map = service.seats(1L, 3L, "tok");

        assertThat(map.expiresIn()).isEqualTo(301L);
        assertThat(map.expiresAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusSeconds(301));
        assertThat(CACHED.expiresIn()).isNull();   // 캐시 값에는 타이머가 없음 — 남의 타이머가 섞이지 않게
        verify(cache, never()).put(any());
    }

    /** 대기열을 끈 공연 — 토큰 없이 배치만 (타이머 없음) */
    @Test
    void noTokenNoTimer() {
        when(cache.get(1L)).thenReturn(Optional.of(CACHED));

        assertThat(service.seats(1L, 3L, null).expiresIn()).isNull();
        verify(queue, never()).entryLeft(anyLong(), anyLong(), anyString());
    }

    /** 입장 시간이 지났으면 배치도 주지 않고 대기열 에러 — 프론트는 대기열로 돌아갑니다 */
    @Test
    void expiredTokenIsRejectedBeforeMap() {
        when(queue.entryLeft(1L, 3L, "old")).thenThrow(new GeneralException(QueueErrorCode.QUEUE_NOT_ADMITTED));

        assertThatThrownBy(() -> service.seats(1L, 3L, "old"))
                .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getErrorCode().getCode()).isEqualTo("QUEUE_NOT_ADMITTED"));
        verify(cache, never()).get(anyLong());
    }

    /** 캐시가 비면 층 → 구역(모양) → 좌석으로 묶어 저장 · 파는 좌석이 없는 구역도 모양은 보냄 · available 은 SOLD 제외 */
    @Test
    void mapGroupsSeatsByFloorAndSection() {
        ConcertEntity concert = mock(ConcertEntity.class);
        when(concert.getVenueId()).thenReturn(5L);
        when(concert.getMaxSeatsPerUser()).thenReturn(2);
        when(concerts.findById(1L)).thenReturn(Optional.of(concert));
        when(cache.get(1L)).thenReturn(Optional.empty());
        List<LayoutRepository.SectionRow> sections = List.of(section(10L, "1F", 100L, "A"), section(10L, "1F", 101L, "B"),
                section(11L, "2F", 200L, "A"));
        List<ConcertSeatRepository.SeatMapRow> rows = List.of(seat(1000L, 100L, "AVAILABLE"), seat(1001L, 100L, "SOLD"),
                seat(2000L, 200L, "HELD"));
        when(layout.findSections(5L)).thenReturn(sections);
        when(seats.findSeatMap(1L)).thenReturn(rows);

        SeatMap map = service.seats(1L, 3L, null);

        assertThat(map.maxSeatsPerUser()).isEqualTo(2);
        assertThat(map.total()).isEqualTo(3);
        assertThat(map.available()).isEqualTo(2);
        assertThat(map.floors()).extracting(f -> f.name() + ":" + f.sections().size()).containsExactly("1F:2", "2F:1");
        assertThat(map.floors().get(0).sections().get(0).seats()).hasSize(2);
        assertThat(map.floors().get(0).sections().get(1).seats()).isEmpty();
        assertThat(map.floors().get(0).sections().get(0).shape()).hasSize(3);
        verify(cache).put(map);
    }

    private static LayoutRepository.SectionRow section(long floorId, String floor, long id, String name) {
        LayoutRepository.SectionRow r = mock(LayoutRepository.SectionRow.class);
        when(r.getFloorId()).thenReturn(floorId);
        when(r.getFloorName()).thenReturn(floor);
        when(r.getId()).thenReturn(id);
        when(r.getName()).thenReturn(name);
        when(r.getShape()).thenReturn("[[0.1, 0.1], [0.2, 0.1], [0.2, 0.3]]");
        when(r.getAngle()).thenReturn(new BigDecimal("12.5"));
        return r;
    }

    private static ConcertSeatRepository.SeatMapRow seat(long id, long sectionId, String status) {
        ConcertSeatRepository.SeatMapRow r = mock(ConcertSeatRepository.SeatMapRow.class);
        when(r.getId()).thenReturn(id);
        when(r.getSectionId()).thenReturn(sectionId);
        when(r.getRowNo()).thenReturn(1);
        when(r.getSeatNo()).thenReturn((int) (id % 100) + 1);
        when(r.getX()).thenReturn(new BigDecimal("0.15"));
        when(r.getY()).thenReturn(new BigDecimal("0.2"));
        when(r.getAngle()).thenReturn(new BigDecimal("12.5"));
        when(r.getPrice()).thenReturn(55000);
        when(r.getStatus()).thenReturn(status);
        return r;
    }
}
