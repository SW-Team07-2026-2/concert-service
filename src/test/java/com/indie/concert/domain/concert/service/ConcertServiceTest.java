package com.indie.concert.domain.concert.service;

import com.indie.concert.domain.concert.dto.response.ConcertSummary;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.Region;
import com.indie.concert.domain.concert.entity.VenueEntity;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.concert.repository.VenueRepository;
import com.indie.concert.global.config.ConcertProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 11/19 목록 카드에 포스터를 그리게 — 목록 · 캘린더 응답에 상세와 같은 posterUrl (없으면 null) */
class ConcertServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-19T12:00:00+09:00");
    private static final OffsetDateTime STARTS = OffsetDateTime.parse("2026-12-20T19:00:00+09:00");
    private static final String POSTER = "https://img.indig.co.kr/poster/2026/11/00000000-0000-0000-0000-000000000000.jpg";

    private final ConcertRepository concerts = mock(ConcertRepository.class);
    private final VenueRepository venues = mock(VenueRepository.class);
    private final LineupReader lineups = mock(LineupReader.class);
    private final ConcertService service = new ConcertService(concerts, venues, lineups,
            new ConcertProperties(null, null, null, "Asia/Seoul", null, null, null, null, null, null, null),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

    @Test
    void listCarriesPosterUrl() {
        VenueEntity venue = new VenueEntity("공연장", Region.SEOUL, null, 100);
        ReflectionTestUtils.setField(venue, "id", 7L);
        when(venues.findAllById(any())).thenReturn(List.of(venue));
        when(lineups.of(anyCollection())).thenReturn(Map.of());
        when(concerts.search(any(), any())).thenReturn(new SliceImpl<>(List.of(concert(1L, POSTER), concert(2L, null))));

        List<ConcertSummary> items = service.list(null, null, null, null, null, null, null, null, null, null, 0, 20).items();

        assertThat(items).extracting(ConcertSummary::id).containsExactly(1L, 2L);
        assertThat(items).extracting(ConcertSummary::posterUrl).containsExactly(POSTER, null);
    }

    private static ConcertEntity concert(long id, String posterUrl) {
        ConcertEntity c = new ConcertEntity(7L, "공연 " + id, ConcertEntity.Type.SOLO, "INDIE_ROCK", STARTS, null, 55000,
                NOW.minusDays(1), true, 4, null, null, NOW);
        ReflectionTestUtils.setField(c, "id", id);
        c.describe(null, null, null, posterUrl, null, NOW);
        return c;
    }
}
