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
import com.indie.concert.domain.seat.repository.SeatRepository;
import com.indie.concert.global.common.exception.CommonErrorCode;
import com.indie.concert.global.common.exception.GeneralException;
import com.indie.concert.global.common.response.CursorPage;
import com.indie.concert.global.common.util.Cursor;
import com.indie.concert.global.config.ConcertProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 예매 — Saga ① (11 §4 · 15 §5.1 의 준비 → 승인 흐름). Concert 가 흐름을 지휘하고 보상(좌석 반환)까지 책임집니다.
 * <pre>
 * 좌석 선점 (11/04 77A · 티켓링크 방식) — 예매 PENDING = 선점 (holdId = bookingId)
 * ① 좌석 고름(N10) → 조건부 UPDATE 로 한 명만 선점 (화면 = 입장 토큰 남은 시간 · 서버 = 화면 + 유예 · DB now()) + Payment prepare   진 사람은 바로 409 SEAT_TAKEN
 * ② 결제 승인(N16) → 내 선점이고 서버 마감 전일 때만 HELD → PAYING (아니면 410 · 토스를 부르지 않음) → Payment 내부 승인
 *    APPROVED: CONFIRMED · SOLD   거절: PAYING → HELD · 새 결제(새 pgOrderId)로 다시 (80B)   응답 없음: PAYING 그대로 503
 * ③ 정리(30초) — 마감이 지난 선점 · 1분 넘게 응답 없는 PAYING → Payment 에 먼저 묻고 → 승인이면 확정, 아니면 EXPIRED · 좌석 반환
 * </pre>
 * 마감이 지난 선점은 정리를 기다리지 않고 다음 사람이 바로 가져갑니다 — 그 예매의 승인은 ②에서 막히므로 돈이 나가지 않습니다.
 * 같은 사용자의 동시 요청은 uq_booking_active 가 줄을 세웁니다 (사용자 락 없음 — 11/04 L2 해소).
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
    private final BookingEvents events;
    private final TransactionTemplate tx;
    private final ConcertProperties.Booking props;
    private final Clock clock;

    public BookingService(BookingRepository bookingRepository, BookingSeatRepository bookingSeatRepository,
                          SeatRepository seatRepository, ConcertRepository concertRepository, VenueRepository venueRepository,
                          QueueService queueService, PaymentClient paymentClient, BookingEvents events,
                          TransactionTemplate tx, ConcertProperties props, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.bookingSeatRepository = bookingSeatRepository;
        this.seatRepository = seatRepository;
        this.concertRepository = concertRepository;
        this.venueRepository = venueRepository;
        this.queueService = queueService;
        this.paymentClient = paymentClient;
        this.events = events;
        this.tx = tx;
        this.props = props.booking();
        this.clock = clock;
    }

    /**
     * N10 — 좌석 선점 = 예매 PENDING + 결제 준비. 🔴 금액은 seat.price 로만 정합니다 (요청에 금액 없음). 입장 토큰은 여기서만 봅니다 — 선점 마감이 토큰 남은 시간을 이어받아 입장부터 결제하기까지 시계가 하나입니다.
     * 같은 좌석으로 다시 부르면 진행 중인 선점을 그대로 (더블클릭 · 탭 2개 · 타이머 리셋 없음).
     * 다른 좌석이면 409 HOLD_EXISTS + 내 bookingId — 「이전단계」는 N13 으로 선택을 취소한 뒤 다시 고릅니다.
     */
    public BookingCreated create(long concertId, long userId, String entryToken, long seatId) {
        ConcertEntity concert = concertRepository.findById(concertId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        OptionalLong entryLeft = queueService.requireEntry(concert, userId, entryToken);
        SeatEntity seat = seatRepository.findById(seatId).filter(s -> s.getConcertId().equals(concertId))
                .orElseThrow(() -> new GeneralException(SeatErrorCode.SEAT_NOT_FOUND));
        Optional<BookingEntity> mine = bookingRepository.findActive(concertId, userId);
        if (mine.isPresent()) {
            Optional<BookingCreated> kept = keepOrClear(mine.get(), seatId);
            if (kept.isPresent()) return kept.get();
        }
        BookingEntity booking;
        try {
            booking = tx.execute(s -> {
                BookingEntity b = bookingRepository.save(new BookingEntity(userId, concertId, seat.getPrice(), OffsetDateTime.now(clock)));
                bookingRepository.startHold(b.getId(), holdSeconds(entryLeft));
                bookingSeatRepository.save(new BookingSeatEntity(b.getId(), seatId));
                if (seatRepository.hold(seatId, concertId, b.getId(), userId) == 0) {
                    throw new GeneralException(SeatErrorCode.SEAT_TAKEN);   // 롤백 — 예매도 남지 않음
                }
                return b;
            });
        } catch (DataIntegrityViolationException e) {
            // 같은 사용자의 동시 요청 — uq_booking_active 가 먼저 들어간 쪽을 기다렸다가 막습니다. 그 선점을 그대로 돌려줍니다
            BookingEntity first = bookingRepository.findActive(concertId, userId).orElseThrow(() -> e);
            return keepOrClear(first, seatId).orElseThrow(() -> e);
        }
        return created(bookingRepository.findById(booking.getId()).orElseThrow(), prepared(booking.getId(), userId, booking.getTotalPrice(), true));
    }

    /**
     * N16 — 결제 승인. 입장 토큰은 보지 않고 선점(bookingId)만 봅니다. ★ 내 선점이고 서버 마감 전이어야 PAYING 으로 바꾸고,
     * 그 뒤에만 Payment 승인을 부릅니다 — 마감이 지났으면 410 HOLD_EXPIRED 이고 토스는 호출하지 않습니다 (돈 안 나감).
     */
    public BookingPaid pay(long userId, long bookingId, String paymentKey, int amount, String forceDecline) {
        BookingEntity b = bookingRepository.findByIdAndUserId(bookingId, userId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        if (b.getStatus() == BookingEntity.Status.CONFIRMED) throw new GeneralException(BookingErrorCode.ALREADY_PAID);
        if (b.getStatus() == BookingEntity.Status.CANCELLED) throw new GeneralException(BookingErrorCode.BOOKING_CANCELLED);
        if (b.getStatus() == BookingEntity.Status.EXPIRED) throw new GeneralException(BookingErrorCode.HOLD_EXPIRED);
        Integer started = tx.execute(s -> {
            int n = seatRepository.startPaying(bookingId, userId);
            if (n > 0) bookingRepository.markPaying(bookingId);
            return n;
        });
        if (started == null || started == 0) {
            boolean paying = b.getPayingAt() != null;
            throw new GeneralException(paying ? SeatErrorCode.SEAT_LOCKED : BookingErrorCode.HOLD_EXPIRED);   // 내 승인이 진행 중(연타) · 마감 지남 · 남이 가져감
        }
        PaymentClient.Confirmed result;
        try {
            result = paymentClient.confirm(bookingId, userId, paymentKey, amount, forceDecline);
        } catch (PaymentClient.Rejected e) {
            stopPaying(bookingId);   // 금액 위조 · 결제 만료 등 — 돈이 나가지 않았으니 다시 선점으로 (마감 그대로)
            throw new GeneralException(e.errorCode(), e.getMessage());
        } catch (PaymentClient.Unavailable e) {
            // 승인됐는지 모릅니다 — 좌석은 PAYING 그대로(남이 못 가져감). 1분 뒤 정리가 Payment 에 물어 확정 · 반환합니다
            log.warn("payment_confirm_unknown bookingId={} error={}", bookingId, e.getMessage());
            throw new GeneralException(BookingErrorCode.PAYMENT_PENDING);
        }
        if (result.approved()) {
            confirm(bookingId);         // payment.completed 를 기다리지 않음 — 이벤트는 조건부 UPDATE 0행으로 끝남
            return new BookingPaid(bookingId, "CONFIRMED", result.status(), result.reason(), null);
        }
        // 거절 (한도초과 · 잔액부족 … — 토스 승인 단계에서 남) → 선점은 유지하고 새 결제로 다시 (80B)
        stopPaying(bookingId);
        BookingEntity now = bookingRepository.findById(bookingId).orElseThrow();
        if (!holdOpen(now)) {
            release(bookingId, true);   // 마감까지 지남 — 거절된 결제라 돈이 나갈 수 없어 바로 반환
            return new BookingPaid(bookingId, "EXPIRED", result.status(), result.reason(), null);
        }
        return new BookingPaid(bookingId, "PENDING", result.status(), result.reason(),
                prepared(bookingId, userId, now.getTotalPrice(), false));
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
                if (sold == 0) log.error("booking_confirmed_without_paying_seat bookingId={}", bookingId);   // 승인은 PAYING 좌석에서만 — 생기면 수동 확인
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

    /** payment.cancelled — 이미 닫힌 예매면 아무것도 하지 않습니다 (좌석 이중 반환 방지) */
    public boolean cancelLocally(long bookingId) {
        return release(bookingId, false);
    }

    /**
     * 정리 (30초) — ① 결제 승인 응답을 못 받은 채 1분이 지난 PAYING ② 서버 마감이 지난 선점.
     * Payment 를 먼저 끝냅니다 (refundApproved=false) — 승인됐으면 APPROVED 가 돌아오고 확정(SOLD), 아니면 EXPIRED · 좌석 반환.
     * Payment 는 결과를 모르는 승인을 토스에 조회해서 답합니다 (84A) — 토스가 아직 처리 중이면 503 → 그 건만 다음 주기에.
     */
    public void expirePending(int batch) {
        for (Long bookingId : bookingRepository.findStalePaying(props.payingCheckSeconds(), batch)) {
            settle(bookingId);   // 건마다 — 한 건이 토스 처리 중이어도 나머지는 진행
        }
        for (Long bookingId : bookingRepository.findExpiredHolds(batch)) {
            if (!settle(bookingId)) return;   // Payment 불통 — 살아나면 다음 주기에 이어서
        }
    }

    /** false = Payment 응답 없음 (결과를 모름) — 좌석은 그대로 둡니다 */
    private boolean settle(long bookingId) {
        String status;
        try {
            status = paymentClient.cancel(bookingId, "EXPIRED", false);
        } catch (PaymentClient.Unavailable e) {
            log.warn("booking_expiry_paused bookingId={} error={}", bookingId, e.getMessage());
            return false;
        }
        if ("APPROVED".equals(status)) {
            log.info("booking_expiry_found_paid bookingId={}", bookingId);
            confirm(bookingId);
            return true;
        }
        release(bookingId, true);
        return true;
    }

    /**
     * 이 공연의 내 예매가 이미 있을 때. 확정이면 409 (1인 1석). 살아 있는 선점(또는 결제 중)이면 같은 좌석은 그대로 · 다른 좌석은 409 HOLD_EXISTS.
     * 마감이 지난 선점이면 먼저 정리(결제 무효화 → EXPIRED)하고 empty — 새로 선점하게 (정리 주기를 기다리지 않음)
     */
    private Optional<BookingCreated> keepOrClear(BookingEntity b, long seatId) {
        if (b.getStatus() == BookingEntity.Status.CONFIRMED) throw new GeneralException(SeatErrorCode.SEAT_LIMIT_EXCEEDED);
        if (b.getPayingAt() != null || holdOpen(b)) {
            if (seatOf(b.getId()) != seatId) {
                throw new GeneralException(BookingErrorCode.HOLD_EXISTS).with("bookingId", b.getId());
            }
            String pgOrderId = b.getPgOrderId() != null ? b.getPgOrderId() : prepared(b.getId(), b.getUserId(), b.getTotalPrice(), false);
            return Optional.of(created(b, pgOrderId));
        }
        if ("APPROVED".equals(cancelPayment(b.getId(), "EXPIRED", false))) {
            confirm(b.getId());
            throw new GeneralException(BookingErrorCode.ALREADY_PAID);
        }
        release(b.getId(), true);
        return Optional.empty();
    }

    /**
     * 결제 준비 — 같은 예매로 다시 불러도 같은 결과(Payment 멱등). 마지막 결제가 거절이면 Payment 가 새 주문번호를 줍니다 (80B).
     * 거절 뒤에는 먼저 이전 주문번호를 떼어 둡니다 — 준비가 실패해도 다음 N10 이 다시 준비하게.
     * Payment 불통: 새 선점(fresh)이면 선점을 풀고 503, 이어서 쓰는 선점이면 그대로 두고 503
     */
    private String prepared(long bookingId, long userId, int amount, boolean fresh) {
        if (!fresh) tx.executeWithoutResult(s -> bookingRepository.attachPgOrderId(bookingId, null));
        PaymentClient.Prepared prepared;
        try {
            prepared = paymentClient.prepare(bookingId, userId, amount);
        } catch (PaymentClient.Unavailable e) {
            log.warn("payment_prepare_failed bookingId={} error={}", bookingId, e.getMessage());
            if (fresh) release(bookingId, false);   // 결제 준비 전이라 돈이 나갈 수 없습니다 — 선점만 풀고 다시 시도하게
            throw paymentUnavailable("결제를 준비하지 못했습니다. 잠시 후 다시 시도해주세요");
        }
        tx.executeWithoutResult(s -> bookingRepository.attachPgOrderId(bookingId, prepared.pgOrderId()));
        return prepared.pgOrderId();
    }

    private void stopPaying(long bookingId) {
        tx.executeWithoutResult(s -> {
            seatRepository.stopPaying(bookingId);
            bookingRepository.clearPaying(bookingId);
        });
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

    /** 화면 마감 = 서버 마감 − 유예 — 프론트 타이머가 0 이 되는 시각 */
    private OffsetDateTime expiresAt(BookingEntity b) {
        return b.getHoldExpiresAt().minusSeconds(props.holdGraceSeconds());
    }

    /** 화면 마감까지 남은 초 (DB 시계 기준) — 프론트는 이 값으로 타이머를 돌립니다 (기기 시계가 틀려도 맞음) */
    private long expiresIn(BookingEntity b) {
        return Math.max(0, Duration.between(dbNow(), expiresAt(b)).toSeconds());
    }

    /** 서버 마감 전인가 (DB now()) */
    private boolean holdOpen(BookingEntity b) {
        return b.getHoldExpiresAt() != null && b.getHoldExpiresAt().isAfter(dbNow());
    }

    /** 지금부터 서버 마감까지 초 = 화면 시계(입장 토큰 남은 초 · 최대 hold-minutes) + 유예. 대기열을 끈 공연은 hold-minutes 전체 */
    private long holdSeconds(OptionalLong entryLeft) {
        long screen = props.holdMinutes() * 60;
        return Math.min(screen, entryLeft.orElse(screen)) + props.holdGraceSeconds();
    }

    private OffsetDateTime dbNow() {
        return Instant.ofEpochMilli(seatRepository.nowMillis()).atOffset(ZoneOffset.UTC);
    }

    private static GeneralException notCancellable(String message) {
        return new GeneralException(BookingErrorCode.BOOKING_NOT_CANCELLABLE, message);
    }

    private static GeneralException paymentUnavailable(String message) {
        return new GeneralException(BookingErrorCode.PAYMENT_UNAVAILABLE, message);
    }
}
