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
import com.indie.concert.domain.concert.entity.VenueEntity;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.concert.repository.VenueRepository;
import com.indie.concert.domain.seat.repository.ConcertSeatRepository;
import com.indie.concert.global.common.exception.CommonErrorCode;
import com.indie.concert.global.common.exception.GeneralException;
import com.indie.concert.global.config.ConcertProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 입장 (11/01 · 암표 방지) — 앱은 30초짜리 서명 QR 을 20초마다 받고(N17), 검표(TICKET_CHECKER)가 찍으면 한 번만 입장 처리합니다.
 * <pre>
 * 캡처본 되팔기 → QR 30초 만료 (ENTRY_QR_EXPIRED)      같은 QR 두 번 → checked_in_at 조건부 UPDATE (ALREADY_CHECKED_IN)
 * 미리 사서 넘기기 → 공연 3시간 전부터만 발급 (ENTRY_NOT_OPEN)   입장 뒤 환불 → 취소 마감(24시간 전)이 발급 시작보다 앞
 * </pre>
 */
@Service
public class EntryService {

    private static final Logger log = LoggerFactory.getLogger(EntryService.class);

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final ConcertSeatRepository concertSeatRepository;
    private final ConcertRepository concertRepository;
    private final VenueRepository venueRepository;
    private final EntryQrTokens tokens;
    private final ConcertProperties.Entry props;
    private final Clock clock;

    public EntryService(BookingRepository bookingRepository, BookingSeatRepository bookingSeatRepository,
                        ConcertSeatRepository concertSeatRepository, ConcertRepository concertRepository, VenueRepository venueRepository,
                        EntryQrTokens tokens, ConcertProperties props, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.bookingSeatRepository = bookingSeatRepository;
        this.concertSeatRepository = concertSeatRepository;
        this.concertRepository = concertRepository;
        this.venueRepository = venueRepository;
        this.tokens = tokens;
        this.props = props.entry();
        this.clock = clock;
    }

    /** N17 — 결제된 내 예매 · 입장 전 · 공연 3시간 전 ~ 끝 시각(모르면 시작 6시간 뒤)에만. 남의 예매는 404 */
    @Transactional(readOnly = true)
    public EntryQr issue(long userId, long bookingId) {
        BookingEntity b = bookingRepository.findByIdAndUserId(bookingId, userId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        if (b.getStatus() != BookingEntity.Status.CONFIRMED) throw new GeneralException(BookingErrorCode.ENTRY_NOT_AVAILABLE);
        if (b.getCheckedInAt() != null) {
            throw new GeneralException(BookingErrorCode.ALREADY_CHECKED_IN).with("checkedInAt", b.getCheckedInAt());
        }
        ConcertEntity c = concertRepository.findById(b.getConcertId()).orElseThrow();
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime opensAt = c.getStartsAt().minusHours(props.openBeforeHours());
        if (now.isBefore(opensAt)) {
            throw new GeneralException(BookingErrorCode.ENTRY_NOT_OPEN, "공연 시작 " + props.openBeforeHours() + "시간 전부터 입장 QR 이 열립니다")
                    .with("opensAt", opensAt);
        }
        OffsetDateTime closesAt = c.getEndsAt() != null ? c.getEndsAt() : c.getStartsAt().plusHours(props.closeAfterHours());
        if (now.isAfter(closesAt)) throw new GeneralException(BookingErrorCode.ENTRY_CLOSED);
        EntryQrTokens.Issued qr = tokens.issue(b.getId(), c.getId());
        return new EntryQr(qr.token(), OffsetDateTime.ofInstant(qr.expiresAt(), ZoneOffset.UTC));
    }

    /** 검표 화면의 공연 고르기 — 지금 입장이 열린 공연 */
    @Transactional(readOnly = true)
    public List<ConcertRef> openConcerts() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<ConcertEntity> concerts = concertRepository.findEntryOpen(
                now, now.plusHours(props.openBeforeHours()), now.minusHours(props.closeAfterHours()));
        Map<Long, VenueEntity> venues = venueRepository.findAllById(concerts.stream().map(ConcertEntity::getVenueId).distinct().toList())
                .stream().collect(Collectors.toMap(VenueEntity::getId, Function.identity()));
        return concerts.stream().map(c -> new ConcertRef(c.getId(), c.getTitle(), c.getStartsAt(), venues.get(c.getVenueId()).getName())).toList();
    }

    /** 검표 — QR 의 공연이 검표 중인 공연과 같아야 합니다. 입장 기록은 한 번만 (동시에 두 번 찍어도 1행) */
    @Transactional
    public CheckInResult checkIn(long concertId, String token, long checkerId) {
        EntryQrTokens.Claims qr = tokens.verify(token);
        if (qr.concertId() != concertId) throw new GeneralException(BookingErrorCode.WRONG_CONCERT);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (bookingRepository.checkIn(qr.bookingId(), checkerId, now) == 0) {
            BookingEntity b = bookingRepository.findById(qr.bookingId()).orElseThrow(() -> new GeneralException(BookingErrorCode.ENTRY_QR_INVALID));
            if (b.getCheckedInAt() != null) {
                throw new GeneralException(BookingErrorCode.ALREADY_CHECKED_IN).with("checkedInAt", b.getCheckedInAt());
            }
            throw new GeneralException(BookingErrorCode.ENTRY_NOT_AVAILABLE, "취소된 예매입니다");   // QR 을 받은 뒤 환불됨
        }
        log.info("booking_checked_in bookingId={} concertId={} checkerId={}", qr.bookingId(), concertId, checkerId);
        List<SeatRef> seats = concertSeatRepository.findRefs(bookingSeatRepository.findByBookingId(qr.bookingId()).stream()
                        .map(BookingSeatEntity::getConcertSeatId).toList()).stream()
                .map(r -> new SeatRef(r.getId(), r.getFloor(), r.getSection(), r.getRowNo(), r.getSeatNo(), r.getPrice())).toList();
        return new CheckInResult(qr.bookingId(), seats, now);
    }
}
