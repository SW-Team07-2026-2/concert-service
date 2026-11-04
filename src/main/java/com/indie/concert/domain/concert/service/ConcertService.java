package com.indie.concert.domain.concert.service;

import com.indie.concert.domain.concert.dto.response.CalendarDay;
import com.indie.concert.domain.concert.dto.response.ConcertCalendar;
import com.indie.concert.domain.concert.dto.response.ConcertDetail;
import com.indie.concert.domain.concert.dto.response.ConcertSummary;
import com.indie.concert.domain.concert.dto.response.VenueRef;
import com.indie.concert.domain.concert.dto.response.VenueView;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.Region;
import com.indie.concert.domain.concert.entity.VenueEntity;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.concert.repository.VenueRepository;
import com.indie.concert.global.common.exception.CommonErrorCode;
import com.indie.concert.global.common.exception.GeneralException;
import com.indie.concert.global.common.response.PageResult;
import com.indie.concert.global.config.ConcertProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 공연 조회 (N1 ~ N3) — 공개 API. 하루 경계는 Asia/Seoul 입니다 (DB 는 UTC).
 * 아티스트 이름 · 장르는 복제값이라 Catalog 를 부르지 않습니다 (Catalog → Concert 동기 호출도 없음 — 08 §3 의존 규칙).
 */
@Service
public class ConcertService {

    private static final Sort BY_START = Sort.by("startsAt").ascending().and(Sort.by("id").ascending());

    private final ConcertRepository concertRepository;
    private final VenueRepository venueRepository;
    private final ZoneId zone;
    private final Clock clock;

    public ConcertService(ConcertRepository concertRepository, VenueRepository venueRepository,
                          ConcertProperties props, Clock clock) {
        this.concertRepository = concertRepository;
        this.venueRepository = venueRepository;
        this.zone = ZoneId.of(props.zone());
        this.clock = clock;
    }

    /** N1 — from 이 없으면 지금 이후 (지난 공연 제외). to 는 그날까지 포함 */
    @Transactional(readOnly = true)
    public PageResult<ConcertSummary> list(LocalDate from, LocalDate to, Region region, String genre,
                                           ConcertEntity.Type type, Long artistId, int page, int size) {
        OffsetDateTime start = from == null ? OffsetDateTime.now(clock) : startOfDay(from);
        OffsetDateTime end = to == null ? null : startOfDay(to.plusDays(1));
        if (end != null && !end.isAfter(start)) {
            throw new GeneralException(CommonErrorCode.INVALID_REQUEST, "to 는 from 이후여야 합니다");
        }
        var filter = new ConcertRepository.Filter(start, end, region, genre == null ? null : genre.strip(), type, artistId);
        return PageResult.of(concertRepository.search(filter, PageRequest.of(page, size, BY_START)), this::summaries);
    }

    /** N2 — 공연 상세 + 공연장 + 판매 상태 + 예매 오픈 시각 (08 §7) */
    @Transactional(readOnly = true)
    public ConcertDetail get(long id) {
        ConcertEntity c = concertRepository.findById(id).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        VenueEntity v = venueRepository.findById(c.getVenueId()).orElseThrow();
        return new ConcertDetail(c.getId(), c.getTitle(), c.getType().name(), c.getArtistId(), c.getArtistName(),
                c.getArtistGenre(), c.getStartsAt(), c.getPrice(), c.saleStatus(OffsetDateTime.now(clock)).name(),
                c.getBookingOpenAt(), c.isQueueEnabled(),
                new VenueView(v.getId(), v.getName(), v.getRegion().name(), v.getAddress(), v.getCapacity()));
    }

    /** N3 — 월 단위 · 날짜별 묶음. 팔로우 기준 보기는 프론트가 팔로우 목록을 artistIds 로 넘깁니다 (08 §7) */
    @Transactional(readOnly = true)
    public ConcertCalendar calendar(String month, List<Long> artistIds) {
        YearMonth ym;
        try {
            ym = YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new GeneralException(CommonErrorCode.INVALID_REQUEST, "month 는 yyyy-MM 형식입니다");
        }
        List<ConcertEntity> rows = concertRepository.findForCalendar(startOfDay(ym.atDay(1)),
                startOfDay(ym.plusMonths(1).atDay(1)), artistIds);
        Map<LocalDate, List<ConcertSummary>> byDay = new TreeMap<>();
        for (ConcertSummary s : summaries(rows)) {
            byDay.computeIfAbsent(s.startsAt().atZoneSameInstant(zone).toLocalDate(), d -> new ArrayList<>()).add(s);
        }
        return new ConcertCalendar(ym.toString(), byDay.entrySet().stream().map(e -> new CalendarDay(e.getKey(), e.getValue())).toList());
    }

    private List<ConcertSummary> summaries(List<ConcertEntity> rows) {
        Map<Long, VenueEntity> venues = venueRepository.findAllById(rows.stream().map(ConcertEntity::getVenueId).distinct().toList())
                .stream().collect(Collectors.toMap(VenueEntity::getId, Function.identity()));
        OffsetDateTime now = OffsetDateTime.now(clock);
        return rows.stream().map(c -> {
            VenueEntity v = venues.get(c.getVenueId());
            return new ConcertSummary(c.getId(), c.getTitle(), c.getType().name(), c.getArtistId(), c.getArtistName(),
                    c.getArtistGenre(), c.getStartsAt(), c.getPrice(), c.saleStatus(now).name(),
                    new VenueRef(v.getId(), v.getName(), v.getRegion().name()));
        }).toList();
    }

    private OffsetDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay(zone).toOffsetDateTime();
    }
}
