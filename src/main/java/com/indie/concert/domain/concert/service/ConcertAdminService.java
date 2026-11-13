package com.indie.concert.domain.concert.service;

import com.indie.concert.domain.concert.dto.event.ConcertEvents;
import com.indie.concert.domain.concert.dto.request.ConcertCreateRequest;
import com.indie.concert.domain.concert.dto.request.ConcertUpdateRequest;
import com.indie.concert.domain.concert.dto.request.VenueCreateRequest;
import com.indie.concert.domain.concert.dto.response.ArtistRef;
import com.indie.concert.domain.concert.dto.response.ConcertDetail;
import com.indie.concert.domain.concert.dto.response.VenueView;
import com.indie.concert.domain.concert.entity.ArtistEntity;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.VenueEntity;
import com.indie.concert.domain.concert.repository.ArtistRepository;
import com.indie.concert.domain.concert.repository.ConcertArtistRepository;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.concert.repository.VenueRepository;
import com.indie.concert.global.common.exception.CommonErrorCode;
import com.indie.concert.global.common.exception.GeneralException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 관리자(Studio) — 공연장 · 공연 등록과 수정. 공연은 검수를 거쳐 여기로만 들어옵니다 (수집기 직접 적재 없음).
 * <pre>
 * 장르   SOLO     = 라인업 아티스트의 장르 (artist.updated 로 함께 갱신 · 관리자가 정하지 않음)
 *        FESTIVAL = 관리자가 정함 (아티스트 이벤트로 덮어쓰지 않음)
 * 라인업 PUT 으로 전체 교체 — 새로 들어온 아티스트가 있으면 concert.lineup_updated (그 팔로워에게 알림)
 * </pre>
 */
@Service
public class ConcertAdminService {

    private static final Logger log = LoggerFactory.getLogger(ConcertAdminService.class);

    private final VenueRepository venueRepository;
    private final ConcertRepository concertRepository;
    private final ArtistRepository artistRepository;
    private final ConcertArtistRepository concertArtistRepository;
    private final ConcertService concertService;
    private final LineupReader lineupReader;
    private final ConcertEvents events;
    private final Clock clock;

    public ConcertAdminService(VenueRepository venueRepository, ConcertRepository concertRepository,
                               ArtistRepository artistRepository, ConcertArtistRepository concertArtistRepository,
                               ConcertService concertService, LineupReader lineupReader, ConcertEvents events, Clock clock) {
        this.venueRepository = venueRepository;
        this.concertRepository = concertRepository;
        this.artistRepository = artistRepository;
        this.concertArtistRepository = concertArtistRepository;
        this.concertService = concertService;
        this.lineupReader = lineupReader;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public VenueView createVenue(VenueCreateRequest r) {
        String name = r.name().strip();
        venueRepository.findByName(name).ifPresent(v -> {
            throw new GeneralException(CommonErrorCode.CONFLICT, "이미 등록된 공연장입니다").with("venueId", v.getId());
        });
        String address = r.address() == null || r.address().isBlank() ? null : r.address().strip();
        VenueEntity v = venueRepository.save(new VenueEntity(name, r.region(), address, r.capacity()));
        log.info("venue_created venueId={} name={}", v.getId(), name);
        return new VenueView(v.getId(), v.getName(), v.getRegion().name(), v.getAddress(), v.getCapacity());
    }

    /** 등록 + 라인업 + concert.created 를 한 트랜잭션에 */
    @Transactional
    public ConcertDetail create(ConcertCreateRequest r) {
        VenueEntity venue = venueRepository.findById(r.venueId()).orElseThrow(() -> invalid("공연장이 없습니다"));
        if ((r.source() == null) != (r.externalId() == null)) throw invalid("source 와 externalId 는 함께 보내야 합니다");
        if (r.source() != null) {
            concertRepository.findBySourceAndExternalId(r.source(), r.externalId()).ifPresent(c -> {
                throw new GeneralException(CommonErrorCode.CONFLICT, "이미 등록된 수집 공연입니다").with("concertId", c.getId());
            });
        }
        List<ArtistEntity> lineup = lineup(r.type(), r.artistIds());
        if (r.type() == ConcertEntity.Type.SOLO && r.genre() != null) throw soloGenre();
        String genre = r.type() == ConcertEntity.Type.SOLO ? lineup.get(0).getGenre() : r.genre() == null ? null : r.genre().name();
        checkTimes(r.startsAt(), r.endsAt(), r.bookingOpenAt());
        ConcertEntity c = concertRepository.save(new ConcertEntity(venue.getId(), r.title().strip(), r.type(), genre,
                r.startsAt(), r.endsAt(), r.price(), r.bookingOpenAt(), r.queueEnabled() == null || r.queueEnabled(),
                r.maxSeatsPerUser(), r.source(), r.externalId(), OffsetDateTime.now(clock)));
        c.describe(blankToNull(r.description()), blankToNull(r.terms()), blankToNull(r.requirements()), blankToNull(r.posterUrl()),
                r.imageUrls(), OffsetDateTime.now(clock));
        concertArtistRepository.insertLineup(c.getId(), r.artistIds().toArray(Long[]::new));
        events.concertCreated(c, venue, lineup.stream().map(a -> new ArtistRef(a.getId(), a.getName(), a.getGenre())).toList());
        log.info("concert_created concertId={} type={} artists={}", c.getId(), c.getType(), r.artistIds());
        return concertService.get(c.getId());
    }

    @Transactional
    public ConcertDetail update(long id, ConcertUpdateRequest r) {
        ConcertEntity c = concertRepository.findById(id).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        if (r.genre() != null && c.getType() == ConcertEntity.Type.SOLO) throw soloGenre();
        if (r.venueId() != null && !r.venueId().equals(c.getVenueId())) {
            venueRepository.findById(r.venueId()).orElseThrow(() -> invalid("공연장이 없습니다"));
            if (concertRepository.hasSeats(id)) {
                throw new GeneralException(CommonErrorCode.CONFLICT, "공연 좌석을 만든 뒤에는 공연장을 바꿀 수 없습니다");
            }
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        c.update(r.venueId(), r.title() == null ? null : r.title().strip(), r.startsAt(), r.endsAt(), r.price(),
                r.bookingOpenAt(), r.queueEnabled(), r.maxSeatsPerUser(), now);
        c.describe(r.description(), r.terms(), r.requirements(), r.posterUrl(), r.imageUrls(), now);
        if (r.genre() != null) c.changeGenre(r.genre().name(), now);
        checkTimes(c.getStartsAt(), c.getEndsAt(), c.getBookingOpenAt());
        log.info("concert_updated concertId={}", id);
        return concertService.get(id);
    }

    /** 라인업 전체 교체 — 같은 목록이면 아무것도 하지 않습니다 */
    @Transactional
    public ConcertDetail replaceLineup(long id, List<Long> artistIds) {
        ConcertEntity c = concertRepository.findById(id).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        List<ArtistEntity> lineup = lineup(c.getType(), artistIds);
        List<Long> before = lineupReader.of(id).stream().map(ArtistRef::id).toList();
        if (before.equals(artistIds)) return concertService.get(id);
        concertArtistRepository.deleteLineup(id);
        concertArtistRepository.insertLineup(id, artistIds.toArray(Long[]::new));
        if (c.getType() == ConcertEntity.Type.SOLO) c.changeGenre(lineup.get(0).getGenre(), OffsetDateTime.now(clock));
        List<Long> added = artistIds.stream().filter(a -> !before.contains(a)).toList();
        if (!added.isEmpty()) {
            events.lineupUpdated(c, venueRepository.findById(c.getVenueId()).orElseThrow(), artistIds, added);
        }
        log.info("concert_lineup_replaced concertId={} before={} after={}", id, before, artistIds);
        return concertService.get(id);
    }

    /** 중복 없음 · SOLO 는 1명 · 전부 artist 복제에 있어야 합니다 (Catalog artist.updated 가 아직 안 온 아티스트는 400) — 요청 순서대로 */
    private List<ArtistEntity> lineup(ConcertEntity.Type type, List<Long> artistIds) {
        if (new HashSet<>(artistIds).size() != artistIds.size()) throw invalid("라인업에 같은 아티스트가 두 번 있습니다");
        if (type == ConcertEntity.Type.SOLO && artistIds.size() != 1) throw invalid("단독 공연의 라인업은 아티스트 1명입니다");
        Map<Long, ArtistEntity> found = artistRepository.findAllById(artistIds).stream()
                .collect(Collectors.toMap(ArtistEntity::getId, Function.identity()));
        List<Long> missing = artistIds.stream().filter(a -> !found.containsKey(a)).toList();
        if (!missing.isEmpty()) throw invalid("Catalog 에 없는 아티스트입니다").with("artistIds", missing);
        return artistIds.stream().map(found::get).toList();
    }

    private static void checkTimes(OffsetDateTime startsAt, OffsetDateTime endsAt, OffsetDateTime bookingOpenAt) {
        if (endsAt != null && !endsAt.isAfter(startsAt)) throw invalid("endsAt 은 startsAt 이후여야 합니다");
        if (bookingOpenAt != null && !bookingOpenAt.isBefore(startsAt)) throw invalid("bookingOpenAt 은 startsAt 이전이어야 합니다");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static GeneralException soloGenre() {
        return invalid("단독 공연의 장르는 아티스트 장르를 따릅니다 — genre 는 페스티벌만 보냅니다");
    }

    private static GeneralException invalid(String message) {
        return new GeneralException(CommonErrorCode.INVALID_REQUEST, message);
    }
}
