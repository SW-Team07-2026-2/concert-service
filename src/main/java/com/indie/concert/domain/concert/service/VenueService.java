package com.indie.concert.domain.concert.service;

import com.indie.concert.domain.concert.dto.response.ArtistRef;
import com.indie.concert.domain.concert.dto.response.UpcomingConcert;
import com.indie.concert.domain.concert.dto.response.VenueDetail;
import com.indie.concert.domain.concert.dto.response.VenueView;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.Region;
import com.indie.concert.domain.concert.entity.VenueEntity;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.concert.repository.VenueRepository;
import com.indie.concert.global.common.exception.CommonErrorCode;
import com.indie.concert.global.common.exception.GeneralException;
import com.indie.concert.global.common.response.PageResult;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 공연장 (N14 · N15, 10/14 추가) — "이 공연장에서 이번 달에 뭐 하지?" */
@Service
public class VenueService {

    private static final int UPCOMING_LIMIT = 50;

    private final VenueRepository venueRepository;
    private final ConcertRepository concertRepository;
    private final LineupReader lineupReader;
    private final Clock clock;

    public VenueService(VenueRepository venueRepository, ConcertRepository concertRepository, LineupReader lineupReader,
                        Clock clock) {
        this.venueRepository = venueRepository;
        this.concertRepository = concertRepository;
        this.lineupReader = lineupReader;
        this.clock = clock;
    }

    /** N14 — hasUpcoming=true 면 지금 이후 공연이 있는 곳만 */
    @Transactional(readOnly = true)
    public PageResult<VenueView> list(Region region, String q, boolean hasUpcoming, int page, int size) {
        OffsetDateTime upcomingFrom = hasUpcoming ? OffsetDateTime.now(clock) : null;
        return PageResult.of(venueRepository.search(region, q, upcomingFrom, PageRequest.of(page, size, Sort.by("name"))),
                rows -> rows.stream().map(VenueService::view).toList());
    }

    /** N15 — 공연장 상세 + 예정 공연 (최대 50건 · 가까운 순) */
    @Transactional(readOnly = true)
    public VenueDetail get(long id) {
        VenueEntity v = venueRepository.findById(id).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<ConcertEntity> concerts = concertRepository
                .findByVenueIdAndStartsAtGreaterThanEqualOrderByStartsAtAsc(id, now, Limit.of(UPCOMING_LIMIT));
        Map<Long, List<ArtistRef>> lineups = lineupReader.of(concerts.stream().map(ConcertEntity::getId).toList());
        List<UpcomingConcert> upcoming = concerts.stream()
                .map(c -> new UpcomingConcert(c.getId(), c.getTitle(), c.getType().name(),
                        lineups.getOrDefault(c.getId(), List.of()), c.getStartsAt(), c.saleStatus(now).name()))
                .toList();
        return new VenueDetail(v.getId(), v.getName(), v.getRegion().name(), v.getAddress(), v.getCapacity(), upcoming);
    }

    private static VenueView view(VenueEntity v) {
        return new VenueView(v.getId(), v.getName(), v.getRegion().name(), v.getAddress(), v.getCapacity());
    }
}
