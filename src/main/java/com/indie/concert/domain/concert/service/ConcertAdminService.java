package com.indie.concert.domain.concert.service;

import com.indie.concert.domain.booking.repository.BookingRepository;
import com.indie.concert.domain.concert.dto.event.ConcertEvents;
import com.indie.concert.domain.concert.dto.request.ConcertCreateRequest;
import com.indie.concert.domain.concert.dto.request.ConcertUpdateRequest;
import com.indie.concert.domain.concert.dto.request.SessionRequest;
import com.indie.concert.domain.concert.dto.request.VenueCreateRequest;
import com.indie.concert.domain.concert.dto.response.ArtistRef;
import com.indie.concert.domain.concert.dto.response.ConcertDetail;
import com.indie.concert.domain.concert.dto.response.VenueView;
import com.indie.concert.domain.concert.entity.ArtistEntity;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.ConcertSessionEntity;
import com.indie.concert.domain.concert.entity.VenueEntity;
import com.indie.concert.domain.concert.exception.SessionErrorCode;
import com.indie.concert.domain.concert.repository.ArtistRepository;
import com.indie.concert.domain.concert.repository.ConcertArtistRepository;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.concert.repository.ConcertSessionRepository;
import com.indie.concert.domain.concert.repository.VenueRepository;
import com.indie.concert.domain.seat.repository.ConcertSeatRepository;
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
import java.util.Objects;
import java.util.Set;
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
    private final ConcertSessionRepository sessionRepository;
    private final ConcertSeatRepository concertSeatRepository;
    private final BookingRepository bookingRepository;
    private final ArtistRepository artistRepository;
    private final ConcertArtistRepository concertArtistRepository;
    private final ConcertService concertService;
    private final LineupReader lineupReader;
    private final ConcertEvents events;
    private final Clock clock;

    public ConcertAdminService(VenueRepository venueRepository, ConcertRepository concertRepository,
                               ConcertSessionRepository sessionRepository, ConcertSeatRepository concertSeatRepository,
                               BookingRepository bookingRepository, ArtistRepository artistRepository,
                               ConcertArtistRepository concertArtistRepository, ConcertService concertService,
                               LineupReader lineupReader, ConcertEvents events, Clock clock) {
        this.venueRepository = venueRepository;
        this.concertRepository = concertRepository;
        this.sessionRepository = sessionRepository;
        this.concertSeatRepository = concertSeatRepository;
        this.bookingRepository = bookingRepository;
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

    /** 등록 + 회차 + 라인업 + concert.created 를 한 트랜잭션에 (11/27 sessions 가 없으면 startsAt 하나가 회차 하나) */
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
        List<OffsetDateTime> times = newSessionTimes(r.sessions(), r.startsAt());
        checkTimes(times.get(0), r.endsAt(), r.bookingOpenAt());
        ConcertEntity draft = new ConcertEntity(venue.getId(), r.title().strip(), r.type(), genre,
                times.get(0), r.endsAt(), r.price(), r.bookingOpenAt(), r.queueEnabled() == null || r.queueEnabled(),
                r.maxSeatsPerUser(), r.source(), r.externalId(), OffsetDateTime.now(clock));
        draft.schedule(times.get(0), times.get(times.size() - 1), OffsetDateTime.now(clock));
        ConcertEntity c = concertRepository.save(draft);
        for (OffsetDateTime t : times) sessionRepository.save(new ConcertSessionEntity(c.getId(), t));
        c.describe(blankToNull(r.description()), blankToNull(r.terms()), blankToNull(r.requirements()), blankToNull(r.posterUrl()),
                r.imageUrls(), OffsetDateTime.now(clock));
        concertArtistRepository.insertLineup(c.getId(), r.artistIds().toArray(Long[]::new));
        events.concertCreated(c, venue, lineup.stream().map(a -> new ArtistRef(a.getId(), a.getName(), a.getGenre())).toList());
        log.info("concert_created concertId={} type={} artists={} sessions={}", c.getId(), c.getType(), r.artistIds(), times.size());
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
        if (r.sessions() != null && r.startsAt() != null) throw invalid("startsAt 과 sessions 는 하나만 보냅니다");
        OffsetDateTime now = OffsetDateTime.now(clock);
        c.update(r.venueId(), r.title() == null ? null : r.title().strip(), r.endsAt(), r.price(),
                r.bookingOpenAt(), r.queueEnabled(), r.maxSeatsPerUser(), now);
        c.describe(r.description(), r.terms(), r.requirements(), r.posterUrl(), r.imageUrls(), now);
        if (r.genre() != null) c.changeGenre(r.genre().name(), now);
        if (r.sessions() != null) replaceSessions(c, r.sessions(), now);
        else if (r.startsAt() != null) rescheduleOnlySession(c, r.startsAt(), now);
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

    /** 등록 — sessions 가 있으면 그 시각들(시작 순), 없으면 startsAt 하나 */
    private static List<OffsetDateTime> newSessionTimes(List<SessionRequest> sessions, OffsetDateTime startsAt) {
        if (sessions == null) {
            if (startsAt == null) throw invalid("startsAt 또는 sessions 가 필요합니다");
            return List.of(startsAt);
        }
        if (sessions.stream().anyMatch(s -> s.id() != null)) throw invalid("새 공연의 회차에는 id 를 보내지 않습니다");
        return sortedDistinct(sessions);
    }

    /** 같은 순간이면 같은 시각 (+09:00 와 Z 표기가 달라도) */
    private static List<OffsetDateTime> sortedDistinct(List<SessionRequest> sessions) {
        List<OffsetDateTime> times = sessions.stream().map(SessionRequest::startsAt).sorted().toList();
        if (times.stream().map(OffsetDateTime::toInstant).distinct().count() != times.size()) {
            throw invalid("같은 시각의 회차가 두 번 있습니다");
        }
        return times;
    }

    /** 회차가 하나인 공연의 startsAt 수정 — 그 회차의 시각을 바꿉니다 (예매가 있어도 · 회원 알림은 공연 변경 공지로) */
    private void rescheduleOnlySession(ConcertEntity c, OffsetDateTime startsAt, OffsetDateTime now) {
        List<ConcertSessionEntity> sessions = sessionRepository.findByConcertIdOrderByStartsAtAsc(c.getId());
        if (sessions.size() != 1) throw invalid("회차가 여럿인 공연은 sessions 로 바꿉니다");
        sessions.get(0).reschedule(startsAt);
        c.schedule(startsAt, startsAt, now);
    }

    /**
     * 회차 목록 전체 교체 — id 있음: 시각 바꾸기 · id 없음: 새 회차(다른 회차의 좌석 · 가격을 이어받음) · 목록에 없음: 지우기.
     * 예매 기록(상태 무관)이 있는 회차는 지우지 못합니다 (409 SESSION_HAS_BOOKINGS) — 시각은 바꿀 수 있음.
     * 순서: 확인 → 시각 바꾸기 → 새 회차(지울 회차의 좌석에서도 가격을 복사 — 전부 바꾸는 경우에도 가격이 남게) → 지우기.
     * 같은 시각 검사는 커밋 때 (uq_concert_session DEFERRABLE) — 지운 회차와 같은 시각의 새 회차 · 시각 맞바꾸기도 됩니다
     */
    private void replaceSessions(ConcertEntity c, List<SessionRequest> wanted, OffsetDateTime now) {
        List<OffsetDateTime> times = sortedDistinct(wanted);
        Map<Long, ConcertSessionEntity> current = sessionRepository.findByConcertIdOrderByStartsAtAsc(c.getId()).stream()
                .collect(Collectors.toMap(ConcertSessionEntity::getId, Function.identity()));
        List<Long> keptIds = wanted.stream().map(SessionRequest::id).filter(Objects::nonNull).toList();
        if (new HashSet<>(keptIds).size() != keptIds.size()) throw invalid("같은 회차 id 가 두 번 있습니다");
        List<Long> unknown = keptIds.stream().filter(id -> !current.containsKey(id)).toList();
        if (!unknown.isEmpty()) throw new GeneralException(SessionErrorCode.SESSION_NOT_FOUND).with("sessionIds", unknown);
        Set<Long> kept = Set.copyOf(keptIds);
        List<ConcertSessionEntity> removed = current.values().stream().filter(s -> !kept.contains(s.getId())).toList();
        for (ConcertSessionEntity s : removed) {
            if (bookingRepository.existsBySession(s.getId())) {
                throw new GeneralException(SessionErrorCode.SESSION_HAS_BOOKINGS).with("sessionId", s.getId());
            }
        }
        for (SessionRequest w : wanted) {
            if (w.id() != null) current.get(w.id()).reschedule(w.startsAt());
        }
        for (SessionRequest w : wanted) {
            if (w.id() != null) continue;
            ConcertSessionEntity added = sessionRepository.save(new ConcertSessionEntity(c.getId(), w.startsAt()));
            int seats = concertSeatRepository.copySeats(c.getId(), added.getId());
            log.info("concert_session_added concertId={} sessionId={} seats={}", c.getId(), added.getId(), seats);
        }
        for (ConcertSessionEntity s : removed) {
            concertSeatRepository.deleteSessionSeats(s.getId());
            sessionRepository.delete(s);
            log.info("concert_session_removed concertId={} sessionId={}", c.getId(), s.getId());
        }
        c.schedule(times.get(0), times.get(times.size() - 1), now);
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
