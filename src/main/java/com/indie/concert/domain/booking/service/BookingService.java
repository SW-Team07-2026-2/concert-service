package com.indie.concert.domain.booking.service;

import com.indie.concert.domain.booking.client.PaymentClient;
import com.indie.concert.domain.booking.dto.event.BookingEvents;
import com.indie.concert.domain.booking.dto.response.BookingCreated;
import com.indie.concert.domain.booking.dto.response.BookingDetail;
import com.indie.concert.domain.booking.dto.response.BookingPaid;
import com.indie.concert.domain.booking.dto.response.BookingSummary;
import com.indie.concert.domain.booking.dto.response.ConcertRef;
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
import com.indie.concert.domain.queue.service.QueueService;
import com.indie.concert.domain.seat.entity.SeatEntity;
import com.indie.concert.domain.seat.exception.SeatErrorCode;
import com.indie.concert.domain.seat.repository.SeatLock;
import com.indie.concert.domain.seat.repository.SeatRepository;
import com.indie.concert.global.common.exception.CommonErrorCode;
import com.indie.concert.global.common.exception.GeneralException;
import com.indie.concert.global.common.response.CursorPage;
import com.indie.concert.global.common.util.Cursor;
import com.indie.concert.global.config.ConcertProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 예매 — Saga ① (11 §4 · 15 §5.1 의 준비 → 승인 흐름). Concert 가 흐름을 지휘하고 보상(좌석 반환)까지 책임집니다.
 * <pre>
 * 좌석 경합 (10/27 · FR-CON-23) — 미선택 → 경합(여러 명이 고름) → 선택완료(먼저 결제 승인한 한 명)
 * ① 좌석 고름(N10) → booking PENDING + Payment prepare     좌석은 잡지 않음 — 같은 좌석을 여러 명이 고를 수 있음
 * ② ★ 결제 승인(N16) → 좌석 확보 (조건부 UPDATE AVAILABLE → HELD)   0행 = 먼저 결제한 사람이 있음 → 돈이 나가기 전에 409
 * ③ Payment 내부 승인 (동기) → APPROVED: CONFIRMED · SOLD · booking.completed   DECLINED: CANCELLED · 좌석은 다시 경합
 * ④ 5분(+ 유예 60초) 안에 결제하지 않은 선택 · 승인 응답을 못 받은 확보 → 결제 무효화 → EXPIRED (BookingExpiryJob)
 * </pre>
 * 🔴 좌석을 푸는 모든 경로는 Payment 를 먼저 끝냅니다 — 순서가 반대면 "좌석은 다른 사람에게 팔렸는데 결제는 승인됨"이 생깁니다.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final SeatRepository seatRepository;
    private final ConcertRepository concertRepository;
    private final VenueRepository venueRepository;
    private final QueueService queueService;
    private final PaymentClient paymentClient;
    private final SeatLock seatLock;
    private final BookingEvents events;
    private final TransactionTemplate tx;
    private final ConcertProperties.Booking props;
    private final Clock clock;

    public BookingService(BookingRepository bookingRepository, BookingSeatRepository bookingSeatRepository,
                          SeatRepository seatRepository, ConcertRepository concertRepository, VenueRepository venueRepository,
                          QueueService queueService, PaymentClient paymentClient, SeatLock seatLock, BookingEvents events,
                          TransactionTemplate tx, ConcertProperties props, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.bookingSeatRepository = bookingSeatRepository;
        this.seatRepository = seatRepository;
        this.concertRepository = concertRepository;
        this.venueRepository = venueRepository;
        this.queueService = queueService;
        this.paymentClient = paymentClient;
        this.seatLock = seatLock;
        this.events = events;
        this.tx = tx;
        this.props = props.booking();
        this.clock = clock;
    }

    /**
     * N10 — 좌석 고르기(선택완료) = 예매 PENDING + 결제 준비. 🔴 금액은 seat.price 로만 정합니다 (요청에 금액 없음).
     * 좌석을 잡지 않습니다 — 다른 사람도 같은 좌석을 고를 수 있고, 먼저 결제 승인(N16)한 사람이 가져갑니다.
     * 같은 좌석으로 다시 부르면 진행 중인 예매를 그대로 (뒤로가기 · 연타 · 만료 시각도 그대로).
     * 다른 좌석이면 이전 선택의 결제를 무효화하고 새로 만듭니다 (1인 1석 — 확정 예매가 있으면 409).
     */
    public BookingCreated create(long concertId, long userId, String entryToken, long seatId) {
        ConcertEntity concert = concertRepository.findById(concertId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        queueService.requireEntry(concert, userId, entryToken);
        try (SeatLock.Handle ignored = lockOrConflict("concert:lock:user:" + concertId + ":" + userId)) {   // 탭 두 개로 1인 1석을 넘는 것 차단
            Optional<BookingEntity> active = bookingRepository.findActive(concertId, userId);
            if (active.isPresent()) {
                BookingEntity b = active.get();
                if (b.getStatus() == BookingEntity.Status.CONFIRMED) throw new GeneralException(SeatErrorCode.SEAT_LIMIT_EXCEEDED);
                if (b.getPgOrderId() != null && seatOf(b.getId()) == seatId && expiresAt(b).isAfter(OffsetDateTime.now(clock))) {
                    return created(b, b.getPgOrderId());   // 화면 타이머가 남았을 때만 이어서 — 0초 뒤 다시 고르면 새 5분
                }
                drop(b.getId());
            }
            SeatEntity seat = seatRepository.findById(seatId).filter(s -> s.getConcertId().equals(concertId))
                    .orElseThrow(() -> new GeneralException(SeatErrorCode.SEAT_NOT_FOUND));
            if (seat.getStatus() == SeatEntity.Status.SOLD) throw new GeneralException(SeatErrorCode.SEAT_UNAVAILABLE);
            BookingEntity booking = tx.execute(s -> {
                BookingEntity b = bookingRepository.save(new BookingEntity(userId, concertId, seat.getPrice(), OffsetDateTime.now(clock)));
                bookingSeatRepository.save(new BookingSeatEntity(b.getId(), seatId));
                return b;
            });
            PaymentClient.Prepared prepared;
            try {
                prepared = paymentClient.prepare(booking.getId(), userId, booking.getTotalPrice());
            } catch (PaymentClient.Unavailable e) {
                // 결제 준비 전이라 돈이 나갈 수 없습니다 — 예매만 닫고 다시 시도하게 합니다 (좌석은 잡은 적이 없음)
                log.warn("payment_prepare_failed bookingId={} error={}", booking.getId(), e.getMessage());
                tx.executeWithoutResult(s -> bookingRepository.cancel(booking.getId()));
                throw paymentUnavailable("결제를 준비하지 못했습니다. 잠시 후 다시 시도해주세요");
            }
            tx.executeWithoutResult(s -> bookingRepository.attachPgOrderId(booking.getId(), prepared.pgOrderId()));
            return created(booking, prepared.pgOrderId());
        }
    }

    /**
     * N16 — 결제 승인. ★ 좌석을 먼저 확보한 뒤에만 Payment 승인을 부릅니다 — 같은 좌석을 고른 사람 중
     * 먼저 여기 도착한 한 명만 승인되고, 나머지는 돈이 나가기 전에 409 SEAT_UNAVAILABLE ("이미 선택된 좌석입니다").
     */
    public BookingPaid pay(long userId, long bookingId, String paymentKey, int amount, String forceDecline) {
        BookingEntity b = bookingRepository.findByIdAndUserId(bookingId, userId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        if (b.getStatus() == BookingEntity.Status.CONFIRMED) throw new GeneralException(BookingErrorCode.ALREADY_PAID);
        if (b.getStatus() == BookingEntity.Status.CANCELLED) throw new GeneralException(BookingErrorCode.BOOKING_CANCELLED);
        if (b.getStatus() == BookingEntity.Status.EXPIRED) throw new GeneralException(BookingErrorCode.SELECTION_EXPIRED);   // 만료 처리기가 정리함
        if (!isOpen(b)) throw new GeneralException(BookingErrorCode.SELECTION_EXPIRED);   // 서버 마감(5분 + 60초)은 지났는데 처리기(30초)가 아직 안 돎
        long seatId = seatOf(bookingId);
        OffsetDateTime until = OffsetDateTime.now(clock).plusSeconds(props.claimSeconds());
        Integer claimed = tx.execute(s -> seatRepository.claim(seatId, bookingId, userId, until));
        if (claimed == null || claimed == 0) {
            boolean mine = seatRepository.findById(seatId).map(s -> Long.valueOf(bookingId).equals(s.getBookingId())).orElse(false);
            throw new GeneralException(mine ? SeatErrorCode.SEAT_LOCKED : SeatErrorCode.SEAT_UNAVAILABLE);   // 내 승인이 진행 중(연타) · 먼저 결제한 사람
        }
        PaymentClient.Confirmed result;
        try {
            result = paymentClient.confirm(bookingId, userId, paymentKey, amount, forceDecline);
        } catch (PaymentClient.Rejected e) {
            tx.executeWithoutResult(s -> seatRepository.unclaim(bookingId));   // 금액 위조 · 결제 만료 등 — 돈이 나가지 않았으니 다시 경합으로
            throw new GeneralException(e.errorCode(), e.getMessage());
        } catch (PaymentClient.Unavailable e) {
            // 승인됐는지 모릅니다 — 좌석은 확보한 채 두고, claim 시간이 지나면 만료 처리기가 결제 상태를 보고 확정 · 반환합니다
            log.warn("payment_confirm_unknown bookingId={} error={}", bookingId, e.getMessage());
            throw paymentUnavailable("결제 결과를 확인하고 있습니다. 잠시 후 예매 내역을 확인해주세요");
        }
        if (result.approved()) {
            confirm(bookingId);         // payment.completed 를 기다리지 않음 — 이벤트는 조건부 UPDATE 0행으로 끝남
        } else {
            cancelLocally(bookingId);   // 거절 — 이 결제는 다시 쓸 수 없음. 좌석은 다시 경합
        }
        return new BookingPaid(bookingId, result.approved() ? "CONFIRMED" : "CANCELLED", result.status(), result.reason());
    }

    /** N11 — 내 예매 목록 (커서 · 최신순) */
    @Transactional(readOnly = true)
    public CursorPage<BookingSummary> list(long userId, Cursor cursor, int size) {
        Limit limit = Limit.of(size + 1);
        List<BookingEntity> rows = cursor == null
                ? bookingRepository.findByUserIdOrderByIdDesc(userId, limit)
                : bookingRepository.findByUserIdAndIdLessThanOrderByIdDesc(userId, cursor.id(), limit);
        Map<Long, ConcertEntity> concerts = concertRepository.findAllById(rows.stream().map(BookingEntity::getConcertId).distinct().toList())
                .stream().collect(Collectors.toMap(ConcertEntity::getId, Function.identity()));
        List<BookingSummary> items = rows.stream().map(b -> {
            ConcertEntity c = concerts.get(b.getConcertId());
            return new BookingSummary(b.getId(), b.getStatus().name(), b.getTotalPrice(), b.getCreatedAt(),
                    c.getId(), c.getTitle(), c.getStartsAt());
        }).toList();
        return CursorPage.of(items, size, b -> new Cursor(null, b.bookingId()));
    }

    /** N12 — 남의 예매는 404 (403 이 아님 — 존재 여부도 알려주지 않습니다) */
    @Transactional(readOnly = true)
    public BookingDetail get(long userId, long bookingId) {
        BookingEntity b = bookingRepository.findByIdAndUserId(bookingId, userId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        ConcertEntity c = concertRepository.findById(b.getConcertId()).orElseThrow();
        VenueEntity v = venueRepository.findById(c.getVenueId()).orElseThrow();
        List<SeatEntity> seats = bookingSeatRepository.findByBookingId(bookingId).stream()
                .map(bs -> seatRepository.findById(bs.getSeatId()).orElseThrow()).toList();
        return new BookingDetail(b.getId(), b.getStatus().name(), b.getTotalPrice(), b.getPgOrderId(), b.getCreatedAt(),
                b.getStatus() == BookingEntity.Status.PENDING ? expiresAt(b) : null,
                b.getStatus() == BookingEntity.Status.PENDING ? expiresIn(b) : null,
                new ConcertRef(c.getId(), c.getTitle(), c.getStartsAt(), v.getName()),
                seats.stream().map(s -> new SeatRef(s.getId(), s.getSection(), s.getRowNo(), s.getSeatNo(), s.getPrice())).toList());
    }

    /** N13 — 결제 전이면 무효화(선택 취소), 확정이면 공연 24시간 전까지 환불 (FR-CON-14). Payment 를 먼저 취소하고 좌석을 돌려놓습니다 */
    public BookingDetail cancel(long userId, long bookingId) {
        BookingEntity b = bookingRepository.findByIdAndUserId(bookingId, userId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        switch (b.getStatus()) {
            case CANCELLED, EXPIRED -> throw notCancellable("이미 취소된 예매입니다");
            case CONFIRMED -> {
                if (b.getCheckedInAt() != null) throw notCancellable("입장한 예매는 취소할 수 없습니다");   // 마감(24시간 전)을 줄여도 입장 뒤 환불은 막음
                ConcertEntity c = concertRepository.findById(b.getConcertId()).orElseThrow();
                if (!OffsetDateTime.now(clock).isBefore(c.getStartsAt().minusHours(props.cancelDeadlineHours()))) {
                    throw notCancellable("공연 " + props.cancelDeadlineHours() + "시간 전까지만 취소할 수 있습니다");
                }
            }
            case PENDING -> { }
        }
        cancelPayment(bookingId, "USER_CANCEL", true);
        release(bookingId, false);
        return get(userId, bookingId);
    }

    /** payment.completed — 중복 이벤트면 0행 */
    public void confirm(long bookingId) {
        tx.executeWithoutResult(s -> {
            if (bookingRepository.confirm(bookingId) == 1) {
                int sold = seatRepository.sell(bookingId);
                BookingEntity b = bookingRepository.findById(bookingId).orElseThrow();
                ConcertEntity c = concertRepository.findById(b.getConcertId()).orElseThrow();
                events.bookingCompleted(b, c, sold, OffsetDateTime.now(clock));
                log.info("booking_confirmed bookingId={} seats={}", bookingId, sold);
                return;
            }
            bookingRepository.findById(bookingId).ifPresent(b -> {
                if (b.getStatus() == BookingEntity.Status.CANCELLED || b.getStatus() == BookingEntity.Status.EXPIRED) {
                    // 좌석을 푸는 경로는 Payment 를 먼저 취소하므로 정상 흐름에서는 생기지 않습니다 — 생기면 수동 환불 대상
                    log.error("payment_completed_for_closed_booking bookingId={} status={}", bookingId, b.getStatus());
                }
            });
        });
    }

    /** payment.failed · payment.cancelled — 이미 닫힌 예매면 아무것도 하지 않습니다 (좌석 이중 반환 방지) */
    public boolean cancelLocally(long bookingId) {
        return release(bookingId, false);
    }

    /**
     * 서버 마감(선택 5분 + 유예 60초)이 지난 결제 대기 예매 · 승인 응답을 못 받은 확보 정리. Payment 를 먼저 무효화합니다 (refundApproved=false) —
     * 그 사이 결제가 승인됐다면 APPROVED 가 돌아오고 예매는 건드리지 않습니다 (payment.completed 가 곧 확정).
     */
    public void expirePending(int batch) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime selectedBefore = now.minusMinutes(props.selectMinutes()).minusSeconds(props.selectGraceSeconds());
        for (Long bookingId : bookingRepository.findExpiredPending(now, selectedBefore, batch)) {
            String status;
            try {
                status = paymentClient.cancel(bookingId, "EXPIRED", false);
            } catch (PaymentClient.Unavailable e) {
                log.warn("booking_expiry_paused bookingId={} error={}", bookingId, e.getMessage());
                return;   // Payment 가 살아나면 다음 주기에 이어서
            }
            if ("APPROVED".equals(status)) {
                log.info("booking_expiry_skipped_paid bookingId={}", bookingId);
                continue;
            }
            release(bookingId, true);
        }
    }

    /** 이전 선택 정리 — 결제부터 무효화. 그 사이 승인됐다면 409 (확정 예매는 예매 취소 N13 으로만) */
    private void drop(long bookingId) {
        if ("APPROVED".equals(cancelPayment(bookingId, "SEAT_CHANGED", false))) {
            throw new GeneralException(BookingErrorCode.ALREADY_PAID);
        }
        release(bookingId, false);
    }

    /** 예매 닫기 + 좌석 반환 — 예매 전이가 1행일 때만 좌석을 풉니다 (확보 · 판매된 좌석만 바뀜) */
    private boolean release(long bookingId, boolean expired) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            int closed = expired ? bookingRepository.expire(bookingId) : bookingRepository.cancel(bookingId);
            if (closed == 0) return false;
            seatRepository.releaseBooking(bookingId);
            log.info("booking_{} bookingId={}", expired ? "expired" : "cancelled", bookingId);
            return true;
        }));
    }

    /** 결제 행이 없으면(준비 실패) NOT_FOUND — 돈이 나간 적이 없으니 그대로 진행합니다 */
    private String cancelPayment(long bookingId, String reason, boolean refundApproved) {
        try {
            return paymentClient.cancel(bookingId, reason, refundApproved);
        } catch (PaymentClient.Unavailable e) {
            log.warn("payment_cancel_failed bookingId={} error={}", bookingId, e.getMessage());
            throw paymentUnavailable("예매를 취소하지 못했습니다. 잠시 후 다시 시도해주세요");
        }
    }

    /** 예매 하나에 좌석 하나 (1인 1석) */
    private long seatOf(long bookingId) {
        return bookingSeatRepository.findByBookingId(bookingId).get(0).getSeatId();
    }

    private BookingCreated created(BookingEntity b, String pgOrderId) {
        return new BookingCreated(b.getId(), b.getTotalPrice(), pgOrderId, "PENDING", expiresAt(b), expiresIn(b));
    }

    /** 화면 마감 — 프론트 타이머가 0 이 되는 시각 */
    private OffsetDateTime expiresAt(BookingEntity b) {
        return b.getCreatedAt().plusMinutes(props.selectMinutes());
    }

    /** 화면 마감까지 남은 초 — 프론트는 이 값으로 타이머를 돌립니다 (기기 시계가 틀려도 맞음) */
    private long expiresIn(BookingEntity b) {
        return Math.max(0, Duration.between(OffsetDateTime.now(clock), expiresAt(b)).toSeconds());
    }

    /** 서버 마감 = 화면 마감 + 유예 — 결제창 안에서 0초를 넘긴 사람도 받아 줍니다 (돈은 N16 뒤에 나감) */
    private boolean isOpen(BookingEntity b) {
        return expiresAt(b).plusSeconds(props.selectGraceSeconds()).isAfter(OffsetDateTime.now(clock));
    }

    private SeatLock.Handle lockOrConflict(String key) {
        SeatLock.Handle handle = seatLock.tryLock(key, Duration.ofMillis(props.lockTtlMs()));
        if (handle == null) throw new GeneralException(SeatErrorCode.SEAT_LOCKED);
        return handle;
    }

    private static GeneralException notCancellable(String message) {
        return new GeneralException(BookingErrorCode.BOOKING_NOT_CANCELLABLE, message);
    }

    private static GeneralException paymentUnavailable(String message) {
        return new GeneralException(BookingErrorCode.PAYMENT_UNAVAILABLE, message);
    }
}
