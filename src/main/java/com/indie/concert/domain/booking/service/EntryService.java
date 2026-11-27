package com.indie.concert.domain.booking.service;

import com.indie.concert.domain.booking.dto.response.CheckInResult;
import com.indie.concert.domain.booking.dto.response.ConcertRef;
import com.indie.concert.domain.booking.dto.response.EntryQr;
import com.indie.concert.domain.booking.dto.response.SeatRef;
import com.indie.concert.domain.booking.entity.BookingEntity;
import com.indie.concert.domain.booking.entity.BookingSeatEntity;
import com.indie.concert.domain.booking.exception.BookingErrorCode;
import com.indie.concert.domain.booking.repository.BookingRepository;
import com.indie.concert.domain.booking.repository.BookingSeatRepository;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.ConcertSessionEntity;
import com.indie.concert.domain.concert.entity.VenueEntity;
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
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 입장 (11/01 · 암표 방지) — 앱은 짧은 서명 QR 을 계속 새로 받고(N17), 검표(TICKET_CHECKER)가 찍으면 한 번만 입장 처리합니다.
 * 11/27 입장 시간은 예매한 회차 기준 [시작 − 60분, 시작 + 30분) — 발급 · 검표 모두 이 시간에만 (EntryWindow).
 * <pre>
 * 캡처본 되팔기 → QR 만료 (ENTRY_QR_EXPIRED)                 같은 QR 두 번 → checked_in_at 조건부 UPDATE (ALREADY_CHECKED_IN)
 * 미리 사서 넘기기 → 회차 시작 60분 전부터만 발급 (ENTRY_NOT_OPEN)   입장 뒤 환불 → 취소 마감(24시간 전)이 발급 시작보다 앞
 * 마감 뒤 입장 → 회차 시작 30분 뒤 발급 · 검표 모두 막힘 (ENTRY_CLOSED)   탈퇴 정리 중 → 발급 · 검표 막힘 (ENTRY_NOT_AVAILABLE)
 * </pre>
 */
@Service
public class EntryService {

    private static final Logger log = LoggerFactory.getLogger(EntryService.class);

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final ConcertSeatRepository concertSeatRepository;
    private final ConcertRepository concertRepository;
    private final ConcertSessionRepository sessionRepository;
    private final VenueRepository venueRepository;
    private final EntryQrTokens tokens;
    private final EntryWindow window;
    private final Clock clock;

    public EntryService(BookingRepository bookingRepository, BookingSeatRepository bookingSeatRepository,
                        ConcertSeatRepository concertSeatRepository, ConcertRepository concertRepository,
                        ConcertSessionRepository sessionRepository, VenueRepository venueRepository,
                        EntryQrTokens tokens, EntryWindow window, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.bookingSeatRepository = bookingSeatRepository;
        this.concertSeatRepository = concertSeatRepository;
        this.concertRepository = concertRepository;
        this.sessionRepository = sessionRepository;
        this.venueRepository = venueRepository;
        this.tokens = tokens;
        this.window = window;
        this.clock = clock;
    }

    /** N17 — 결제된 내 예매 · 입장 전 · 회차 입장 시간에만. 남의 예매는 404 */
    @Transactional(readOnly = true)
    public EntryQr issue(long userId, long bookingId) {
        BookingEntity b = bookingRepository.findByIdAndUserId(bookingId, userId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        if (b.getStatus() != BookingEntity.Status.CONFIRMED || b.getWithdrawnAt() != null) {
            throw new GeneralException(BookingErrorCode.ENTRY_NOT_AVAILABLE);
        }
        if (b.getCheckedInAt() != null) {
            throw new GeneralException(BookingErrorCode.ALREADY_CHECKED_IN).with("checkedInAt", b.getCheckedInAt());
        }
        OffsetDateTime startsAt = sessionStartsAt(b);
        requireOpen(startsAt, OffsetDateTime.now(clock));
        EntryQrTokens.Issued qr = tokens.issue(b.getId(), b.getConcertId(), window.closesAt(startsAt).toInstant());
        return new EntryQr(qr.token(), OffsetDateTime.ofInstant(qr.expiresAt(), ZoneOffset.UTC));
    }

    /**
     * 검표 화면의 공연 고르기 — 지금 입장이 열린 회차가 있는 공연. 검표는 공연 단위라 공연마다 한 줄
     * (회차 둘이 동시에 열려 있으면 먼저 시작하는 회차 · 회차가 90분 넘게 떨어져 있으면 생기지 않음)
     */
    @Transactional(readOnly = true)
    public List<ConcertRef> openConcerts() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Map<Long, ConcertSessionEntity> open = new LinkedHashMap<>();
        for (ConcertSessionEntity s : sessionRepository.findByStartsAtLessThanEqualAndStartsAtGreaterThanOrderByStartsAtAsc(
                now.plusMinutes(window.openBeforeMinutes()), now.minusMinutes(window.closeAfterMinutes()))) {
            open.putIfAbsent(s.getConcertId(), s);
        }
        List<ConcertEntity> concerts = concertRepository.findAllById(open.keySet());
        Map<Long, VenueEntity> venues = venueRepository.findAllById(concerts.stream().map(ConcertEntity::getVenueId).distinct().toList())
                .stream().collect(Collectors.toMap(VenueEntity::getId, Function.identity()));
        Map<Long, ConcertEntity> byId = concerts.stream().collect(Collectors.toMap(ConcertEntity::getId, Function.identity()));
        return open.values().stream().map(s -> {
            ConcertEntity c = byId.get(s.getConcertId());
            return new ConcertRef(c.getId(), c.getTitle(), s.getStartsAt(), venues.get(c.getVenueId()).getName(), s.getId());
        }).toList();
    }

    /**
     * 검표 — 순서: QR 형식 · 서명(ENTRY_QR_INVALID) → 검표 중인 공연(WRONG_CONCERT) → 회차 입장 시간(ENTRY_NOT_OPEN · ENTRY_CLOSED)
     * → QR 만료(ENTRY_QR_EXPIRED) → 입장 기록 (한 번만 · 동시에 두 번 찍어도 1행)
     */
    @Transactional
    public CheckInResult checkIn(long concertId, String token, long checkerId) {
        EntryQrTokens.Claims qr = tokens.parse(token);
        if (qr.concertId() != concertId) throw new GeneralException(BookingErrorCode.WRONG_CONCERT);
        BookingEntity booking = bookingRepository.findById(qr.bookingId()).orElseThrow(() -> new GeneralException(BookingErrorCode.ENTRY_QR_INVALID));
        OffsetDateTime now = OffsetDateTime.now(clock);
        requireOpen(sessionStartsAt(booking), now);
        if (qr.expired(now.toInstant())) throw new GeneralException(BookingErrorCode.ENTRY_QR_EXPIRED);
        if (bookingRepository.checkIn(qr.bookingId(), checkerId, now) == 0) {
            BookingEntity b = bookingRepository.findById(qr.bookingId()).orElseThrow(() -> new GeneralException(BookingErrorCode.ENTRY_QR_INVALID));
            if (b.getCheckedInAt() != null) {
                throw new GeneralException(BookingErrorCode.ALREADY_CHECKED_IN).with("checkedInAt", b.getCheckedInAt());
            }
            throw new GeneralException(BookingErrorCode.ENTRY_NOT_AVAILABLE, "취소된 예매입니다");   // QR 을 받은 뒤 환불 · 탈퇴 정리됨
        }
        log.info("booking_checked_in bookingId={} concertId={} sessionId={} checkerId={}", qr.bookingId(), concertId,
                booking.getSessionId(), checkerId);
        List<SeatRef> seats = concertSeatRepository.findRefs(bookingSeatRepository.findByBookingId(qr.bookingId()).stream()
                        .map(BookingSeatEntity::getConcertSeatId).toList()).stream()
                .map(r -> new SeatRef(r.getId(), r.getFloor(), r.getSection(), r.getRowNo(), r.getSeatNo(), r.getPrice())).toList();
        return new CheckInResult(qr.bookingId(), seats, now);
    }

    /** 입장 시간 밖이면 409 — 열리기 전은 opensAt, 마감 뒤는 closesAt 을 함께 돌려줍니다 */
    private void requireOpen(OffsetDateTime sessionStartsAt, OffsetDateTime now) {
        if (window.notOpenYet(sessionStartsAt, now)) {
            throw new GeneralException(BookingErrorCode.ENTRY_NOT_OPEN,
                    "공연 시작 " + window.openBeforeMinutes() + "분 전부터 입장 QR 이 열립니다").with("opensAt", window.opensAt(sessionStartsAt));
        }
        if (window.closed(sessionStartsAt, now)) {
            throw new GeneralException(BookingErrorCode.ENTRY_CLOSED).with("closesAt", window.closesAt(sessionStartsAt));
        }
    }

    private OffsetDateTime sessionStartsAt(BookingEntity b) {
        return sessionRepository.findById(b.getSessionId()).orElseThrow().getStartsAt();
    }
}
