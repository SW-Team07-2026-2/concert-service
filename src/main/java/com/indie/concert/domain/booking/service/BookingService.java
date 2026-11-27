package com.indie.concert.domain.booking.service;

import com.indie.concert.domain.booking.client.PaymentClient;
import com.indie.concert.domain.booking.dto.event.BookingEvents;
import com.indie.concert.domain.booking.dto.response.BookingCreated;
import com.indie.concert.domain.booking.dto.response.BookingDetail;
import com.indie.concert.domain.booking.dto.response.BookingPaid;
import com.indie.concert.domain.booking.dto.response.BookingSummary;
import com.indie.concert.domain.booking.dto.response.ConcertRef;
import com.indie.concert.domain.booking.dto.response.EntryInfo;
import com.indie.concert.domain.booking.dto.response.SeatRef;
import com.indie.concert.domain.booking.entity.BookingEntity;
import com.indie.concert.domain.booking.entity.BookingSeatEntity;
import com.indie.concert.domain.booking.exception.BookingErrorCode;
import com.indie.concert.domain.booking.repository.BookingRepository;
import com.indie.concert.domain.booking.repository.BookingSeatRepository;
import com.indie.concert.domain.concert.dto.response.ArtistRef;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.ConcertSessionEntity;
import com.indie.concert.domain.concert.entity.VenueEntity;
import com.indie.concert.domain.concert.exception.SessionErrorCode;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.concert.repository.ConcertSessionRepository;
import com.indie.concert.domain.concert.repository.VenueRepository;
import com.indie.concert.domain.concert.service.LineupReader;
import com.indie.concert.domain.queue.service.QueueService;
import com.indie.concert.domain.seat.entity.ConcertSeatEntity;
import com.indie.concert.domain.seat.exception.SeatErrorCode;
import com.indie.concert.domain.seat.repository.ConcertSeatRepository;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 좌석 선점과 Payment 결제 준비, 승인, 취소를 조정합니다.
 * 선점은 PENDING 예매로 관리하며 조건부 상태 변경으로 좌석 중복 판매와 중복 반환을 막습니다.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final ConcertSeatRepository concertSeatRepository;
    private final ConcertRepository concertRepository;
    private final ConcertSessionRepository sessionRepository;
    private final VenueRepository venueRepository;
    private final LineupReader lineupReader;
    private final QueueService queueService;
    private final PaymentClient paymentClient;
    private final BookingEvents events;
    private final EntryWindow entryWindow;
    private final TransactionTemplate tx;
    private final ConcertProperties.Booking props;
    private final Clock clock;

    public BookingService(BookingRepository bookingRepository, BookingSeatRepository bookingSeatRepository,
                          ConcertSeatRepository concertSeatRepository, ConcertRepository concertRepository,
                          ConcertSessionRepository sessionRepository, VenueRepository venueRepository, LineupReader lineupReader,
                          QueueService queueService, PaymentClient paymentClient, BookingEvents events, EntryWindow entryWindow,
                          TransactionTemplate tx, ConcertProperties props, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.bookingSeatRepository = bookingSeatRepository;
        this.concertSeatRepository = concertSeatRepository;
        this.concertRepository = concertRepository;
        this.sessionRepository = sessionRepository;
        this.venueRepository = venueRepository;
        this.lineupReader = lineupReader;
        this.queueService = queueService;
        this.paymentClient = paymentClient;
        this.events = events;
        this.entryWindow = entryWindow;
        this.tx = tx;
        this.props = props.booking();
        this.clock = clock;
    }

    /**
     * 같은 회차의 좌석을 선점하고 Payment에 결제를 준비합니다. 금액은 좌석 가격의 합으로 계산합니다.
     * 같은 좌석의 재요청은 기존 선점을 반환하고 다른 좌석이면 기존 선점을 먼저 취소해야 합니다.
     *
     * @param concertId 공연 ID
     * @param userId 조회하거나 처리할 회원 ID
     * @param entryToken 대기열 통과 후 발급된 입장 토큰
     * @param seatIds 선점할 좌석 ID 목록
     */
    public BookingCreated create(long concertId, long userId, String entryToken, List<Long> seatIds) {
        ConcertEntity concert = concertRepository.findById(concertId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        OptionalLong entryLeft = queueService.requireEntry(concert, userId, entryToken);
        List<Long> ids = seatIds.stream().sorted().distinct().toList();   // id 순서로 잡습니다 - 겹치는 좌석을 동시에 고른 두 사람이 서로 기다리지 않게
        if (ids.size() != seatIds.size()) throw new GeneralException(CommonErrorCode.INVALID_REQUEST, "같은 좌석이 두 번 있습니다");
        if (ids.size() > concert.getMaxSeatsPerUser()) {
            throw new GeneralException(SeatErrorCode.SEAT_LIMIT_EXCEEDED,
                    "이 공연은 1인 " + concert.getMaxSeatsPerUser() + "석까지 예매할 수 있습니다");
        }
        List<ConcertSeatEntity> seats = concertSeatRepository.findAllById(ids).stream()
                .filter(s -> s.getConcertId().equals(concertId)).toList();
        if (seats.size() != ids.size()) throw new GeneralException(SeatErrorCode.SEAT_NOT_FOUND);
        long sessionId = seats.get(0).getSessionId();
        if (seats.stream().anyMatch(s -> s.getSessionId() != sessionId)) throw new GeneralException(SeatErrorCode.SEAT_SESSION_MISMATCH);
        ConcertSessionEntity session = sessionRepository.findById(sessionId).orElseThrow();
        if (!OffsetDateTime.now(clock).isBefore(session.getStartsAt())) throw new GeneralException(SessionErrorCode.SESSION_CLOSED);
        int amount = seats.stream().mapToInt(ConcertSeatEntity::getPrice).sum();
        Optional<BookingCreated> kept = keepOrClear(bookingRepository.findActive(concertId, userId), sessionId, ids);
        if (kept.isPresent()) return kept.get();
        BookingEntity booking;
        try {
            booking = tx.execute(s -> {
                BookingEntity b = bookingRepository.save(new BookingEntity(userId, concertId, sessionId, amount, OffsetDateTime.now(clock)));
                bookingRepository.startHold(b.getId(), holdSeconds(entryLeft));
                for (long id : ids) {
                    bookingSeatRepository.save(new BookingSeatEntity(b.getId(), id));
                    if (concertSeatRepository.hold(id, sessionId, b.getId(), userId) == 0) {
                        throw new GeneralException(SeatErrorCode.SEAT_TAKEN).with("seatId", id);   // 롤백 - 예매, 먼저 잡은 좌석도 남지 않음
                    }
                }
                return b;
            });
        } catch (DataIntegrityViolationException e) {
            // 같은 사용자의 동시 요청 - uq_booking_active 가 먼저 들어간 쪽을 기다렸다가 막습니다. 그 선점을 그대로 돌려줍니다
            return keepOrClear(bookingRepository.findActive(concertId, userId), sessionId, ids).orElseThrow(() -> e);
        }
        return created(bookingRepository.findById(booking.getId()).orElseThrow(), prepared(booking.getId(), userId, booking.getTotalPrice(), true));
    }

    /**
     * N16 - 결제 승인. 입장 토큰은 보지 않고 선점(bookingId)만 봅니다. 내 선점이고 서버 마감 전이어야 PAYING 으로 바꾸고,
     * 그 뒤에만 Payment 승인을 부릅니다 - 마감이 지났으면 410 HOLD_EXPIRED 이고 토스는 호출하지 않습니다 (돈 안 나감).
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param bookingId 예매 ID
     * @param paymentKey 토스 결제창 인증 후 발급된 결제 키
     * @param amount 결제 금액(원)
     * @param forceDecline 로컬 테스트용 PG 강제 거절 코드
     */
    public BookingPaid pay(long userId, long bookingId, String paymentKey, int amount, String forceDecline) {
        rejectClosed(bookingRepository.findByIdAndUserId(bookingId, userId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND)));
        Boolean started = tx.execute(s -> {
            // booking 먼저 잠급니다 - 확정, 반환(booking -> seat)과 같은 순서라 같은 예매에 결제와 취소가 동시에 와도 교착이 없습니다
            if (bookingRepository.markPaying(bookingId) == 0) return false;
            if (concertSeatRepository.startPaying(bookingId, userId) > 0) return true;
            s.setRollbackOnly();   // 마감 지남, 남이 가져감, 이미 결제 중 - paying_at 도 되돌림
            return false;
        });
        if (!Boolean.TRUE.equals(started)) {
            // 트랜잭션 뒤에 다시 읽습니다 - 동시에 온 앞 요청이 결제 중으로 바꿨으면 연타(409), 아니면 마감 지남, 남이 가져감(410)
            BookingEntity latest = bookingRepository.findById(bookingId).orElseThrow();
            rejectClosed(latest);
            throw new GeneralException(latest.getPayingAt() != null ? SeatErrorCode.SEAT_LOCKED : BookingErrorCode.HOLD_EXPIRED);
        }
        PaymentClient.Confirmed result;
        try {
            result = paymentClient.confirm(bookingId, userId, paymentKey, amount, forceDecline);
        } catch (PaymentClient.Rejected e) {
            stopPaying(bookingId);   // 금액 위조, 결제 만료 등 - 돈이 나가지 않았으니 다시 선점으로 (마감 그대로)
            throw new GeneralException(e.errorCode(), e.getMessage());
        } catch (PaymentClient.Unavailable e) {
            // 승인됐는지 모릅니다 - 좌석은 PAYING 그대로(남이 못 가져감). 1분 뒤 정리가 Payment 에 물어 확정, 반환합니다
            log.warn("payment_confirm_unknown bookingId={} error={}", bookingId, e.getMessage());
            throw new GeneralException(BookingErrorCode.PAYMENT_PENDING);
        }
        if (result.approved()) {
            confirm(bookingId);         // payment.completed 를 기다리지 않음 - 이벤트는 조건부 UPDATE 0행으로 끝남
            return new BookingPaid(bookingId, "CONFIRMED", result.status(), result.reason(), null);
        }
        // 거절 (한도초과, 잔액부족 ... - 토스 승인 단계에서 남) -> 선점은 유지하고 새 결제로 다시 (80B)
        stopPaying(bookingId);
        BookingEntity now = bookingRepository.findById(bookingId).orElseThrow();
        if (!holdOpen(now)) {
            release(bookingId, true);   // 마감까지 지남 - 거절된 결제라 돈이 나갈 수 없어 바로 반환
            return new BookingPaid(bookingId, "EXPIRED", result.status(), result.reason(), null);
        }
        return new BookingPaid(bookingId, "PENDING", result.status(), result.reason(),
                prepared(bookingId, userId, now.getTotalPrice(), false));
    }

    /**
     * N11 - 내 예매 목록 (커서, 최신순)
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param cursor 다음 조회 시작점을 나타내는 커서
     * @param size 페이지당 조회 개수
     */
    @Transactional(readOnly = true)
    public CursorPage<BookingSummary> list(long userId, Cursor cursor, int size) {
        Limit limit = Limit.of(size + 1);
        List<BookingEntity> rows = cursor == null
                ? bookingRepository.findByUserIdOrderByIdDesc(userId, limit)
                : bookingRepository.findByUserIdAndIdLessThanOrderByIdDesc(userId, cursor.id(), limit);
        Map<Long, ConcertEntity> concerts = concertRepository.findAllById(rows.stream().map(BookingEntity::getConcertId).distinct().toList())
                .stream().collect(Collectors.toMap(ConcertEntity::getId, Function.identity()));
        Map<Long, ConcertSessionEntity> sessions = sessionRepository.findAllById(rows.stream().map(BookingEntity::getSessionId).distinct().toList())
                .stream().collect(Collectors.toMap(ConcertSessionEntity::getId, Function.identity()));
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<BookingSummary> items = rows.stream().map(b -> {
            ConcertEntity c = concerts.get(b.getConcertId());
            ConcertSessionEntity session = sessions.get(b.getSessionId());
            EntryInfo entry = entryWindow.info(b, session.getStartsAt(), now);
            return new BookingSummary(b.getId(), b.getStatus().name(), b.getTotalPrice(), b.getCreatedAt(),
                    c.getId(), c.getTitle(), session.getStartsAt(), b.getSessionId(),
                    entry.entryStatus(), entry.entryQrOpensAt(), entry.entryQrClosesAt(), entry.checkedInAt(), entry.serverTime());
        }).toList();
        return CursorPage.of(items, size, b -> new Cursor(null, b.bookingId()));
    }

    /**
     * N12 - 남의 예매는 404 (403 이 아님 - 존재 여부도 알려주지 않습니다)
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param bookingId 예매 ID
     */
    @Transactional(readOnly = true)
    public BookingDetail get(long userId, long bookingId) {
        BookingEntity b = bookingRepository.findByIdAndUserId(bookingId, userId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        ConcertEntity c = concertRepository.findById(b.getConcertId()).orElseThrow();
        ConcertSessionEntity session = sessionRepository.findById(b.getSessionId()).orElseThrow();
        VenueEntity v = venueRepository.findById(c.getVenueId()).orElseThrow();
        EntryInfo entry = entryWindow.info(b, session.getStartsAt(), OffsetDateTime.now(clock));
        return new BookingDetail(b.getId(), b.getStatus().name(), b.getTotalPrice(), b.getPgOrderId(), b.getCreatedAt(),
                b.getStatus() == BookingEntity.Status.PENDING ? expiresAt(b) : null,
                b.getStatus() == BookingEntity.Status.PENDING ? expiresIn(b) : null,
                new ConcertRef(c.getId(), c.getTitle(), session.getStartsAt(), v.getName(), b.getSessionId()), seatRefs(bookingId),
                entry.entryStatus(), entry.entryQrOpensAt(), entry.entryQrClosesAt(), entry.checkedInAt(), entry.serverTime());
    }

    /**
     * N13 - 결제 전이면 무효화(선택 취소), 확정이면 회차 시작 24시간 전까지 환불 (FR-CON-14). Payment 를 먼저 취소하고 좌석을 돌려놓습니다
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param bookingId 예매 ID
     */
    public BookingDetail cancel(long userId, long bookingId) {
        cancel(bookingRepository.findByIdAndUserId(bookingId, userId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND)),
                bookingId, "USER_CANCEL");
        return get(userId, bookingId);
    }

    /**
     * 관리자(Studio CS)의 취소 대행 - 회원 본인의 취소(N13)와 같은 규칙이고 Payment 에 남는 사유만 다릅니다
     *
     * @param bookingId 예매 ID
     */
    public BookingDetail cancelByAdmin(long bookingId) {
        BookingEntity b = bookingRepository.findById(bookingId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        cancel(b, bookingId, "ADMIN_CANCEL");
        return get(b.getUserId(), bookingId);
    }

    private void cancel(BookingEntity b, long bookingId, String reason) {
        switch (b.getStatus()) {
            case CANCELLED, EXPIRED -> throw notCancellable("이미 취소된 예매입니다");
            case CONFIRMED -> {
                if (b.getCheckedInAt() != null) throw notCancellable("입장한 예매는 취소할 수 없습니다");   // 마감(24시간 전)을 줄여도 입장 뒤 환불은 막음
                if (!OffsetDateTime.now(clock).isBefore(cancelDeadline(b))) {
                    throw notCancellable("공연 " + props.cancelDeadlineHours() + "시간 전까지만 취소할 수 있습니다");
                }
            }
            case PENDING -> { }
        }
        cancelPayment(bookingId, reason, true);
        release(bookingId, false);
    }

    /**
     * payment.completed - 중복 이벤트면 0행
     *
     * @param bookingId 예매 ID
     */
    public void confirm(long bookingId) {
        tx.executeWithoutResult(s -> {
            if (bookingRepository.confirm(bookingId) == 1) {
                int sold = concertSeatRepository.sell(bookingId);
                BookingEntity b = bookingRepository.findById(bookingId).orElseThrow();
                ConcertEntity c = concertRepository.findById(b.getConcertId()).orElseThrow();
                VenueEntity v = venueRepository.findById(c.getVenueId()).orElseThrow();
                if (sold == 0) log.error("booking_confirmed_without_paying_seat bookingId={}", bookingId);   // 승인은 PAYING 좌석에서만 - 생기면 수동 확인
                events.bookingCompleted(b, c, sessionStartsAt(b), v, lineupReader.of(c.getId()).stream().map(ArtistRef::id).toList(),
                        sold, OffsetDateTime.now(clock));
                log.info("booking_confirmed bookingId={} seats={}", bookingId, sold);
                return;
            }
            bookingRepository.findById(bookingId).ifPresent(b -> {
                if (b.getStatus() == BookingEntity.Status.CANCELLED || b.getStatus() == BookingEntity.Status.EXPIRED) {
                    // 좌석을 푸는 경로는 Payment 를 먼저 취소하므로 정상 흐름에서는 생기지 않습니다 - 생기면 수동 환불 대상
                    log.error("payment_completed_for_closed_booking bookingId={} status={}", bookingId, b.getStatus());
                }
            });
        });
    }

    /**
     * payment.cancelled - 이미 닫힌 예매면 아무것도 하지 않습니다 (좌석 이중 반환 방지)
     *
     * @param bookingId 예매 ID
     */
    public boolean cancelLocally(long bookingId) {
        return release(bookingId, false);
    }

    /**
     * 탈퇴 정리 시각을 먼저 저장하고 미입장 예매를 정리합니다.
     * 회차 시작 24시간 전보다 앞이면 환불하고 이후에는 환불 없이 만료합니다. 실패한 처리는 배치가 재시도합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     */
    public void releaseWithdrawn(long userId) {
        tx.executeWithoutResult(s -> bookingRepository.markWithdrawn(userId, OffsetDateTime.now(clock)));
        List<Long> open = bookingRepository.findWithdrawnIds(userId);
        for (Long bookingId : open) {
            if (!settleWithdrawn(bookingId)) break;   // Payment 불통 - 나머지는 재시도 주기에
        }
        if (!open.isEmpty()) log.info("booking_withdrawn userId={} bookings={}", userId, open);
    }

    /**
     * 탈퇴 정리 재시도 - 30초마다 (BookingExpiryJob). 표시는 됐는데 Payment 불통으로 닫지 못한 예매
     *
     * @param batch 한 번에 처리할 최대 예매 수
     */
    public void retryWithdrawn(int batch) {
        for (Long bookingId : bookingRepository.findWithdrawnOpen(batch)) {
            if (!settleWithdrawn(bookingId)) return;   // Payment 불통 - 살아나면 다음 주기에 이어서
        }
    }

    /** false = Payment 응답 없음 (결과를 모름) - 그대로 두고 다음에 다시 */
    private boolean settleWithdrawn(long bookingId) {
        BookingEntity b = bookingRepository.findById(bookingId).orElseThrow();
        if (b.getStatus() == BookingEntity.Status.PENDING) {
            if (!settle(bookingId, "USER_WITHDRAWN")) return false;
            b = bookingRepository.findById(bookingId).orElseThrow();   // 승인돼 있었으면 확정됨 -> 아래 규칙으로
        }
        if (b.getStatus() != BookingEntity.Status.CONFIRMED) return true;
        if (b.getWithdrawnAt().isBefore(cancelDeadline(b))) {
            try {
                paymentClient.cancel(bookingId, "USER_WITHDRAWN", true);
            } catch (PaymentClient.Unavailable e) {
                log.warn("booking_withdrawn_refund_paused bookingId={} error={}", bookingId, e.getMessage());
                return false;
            }
            release(bookingId, false);
            log.info("booking_withdrawn_refunded bookingId={}", bookingId);
            return true;
        }
        if (Boolean.TRUE.equals(tx.execute(s -> bookingRepository.forfeit(bookingId) == 1))) {
            log.info("booking_withdrawn_forfeited bookingId={}", bookingId);
        }
        return true;
    }

    /**
     * 정리 (30초) - 1 결제 승인 응답을 못 받은 채 1분이 지난 PAYING 2 서버 마감이 지난 선점.
     * Payment 를 먼저 끝냅니다 (refundApproved=false) - 승인됐으면 APPROVED 가 돌아오고 확정(SOLD), 아니면 EXPIRED, 좌석 반환.
     * Payment 는 결과를 모르는 승인을 토스에 조회해서 답합니다 (84A) - 토스가 아직 처리 중이면 503 -> 그 건만 다음 주기에.
     *
     * @param batch 한 번에 처리할 최대 예매 수
     */
    public void expirePending(int batch) {
        for (Long bookingId : bookingRepository.findStalePaying(props.payingCheckSeconds(), batch)) {
            settle(bookingId, "EXPIRED");   // 건마다 - 한 건이 토스 처리 중이어도 나머지는 진행
        }
        for (Long bookingId : bookingRepository.findExpiredHolds(batch)) {
            if (!settle(bookingId, "EXPIRED")) return;   // Payment 불통 - 살아나면 다음 주기에 이어서
        }
    }

    /** false = Payment 응답 없음 (결과를 모름) - 좌석은 그대로 둡니다 */
    private boolean settle(long bookingId, String reason) {
        String status;
        try {
            status = paymentClient.cancel(bookingId, reason, false);
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
     * 회차별 확정 예매와 공연별 진행 중 선점을 확인합니다.
     * 같은 좌석의 유효한 선점은 재사용하고 다른 좌석은 HOLD_EXISTS로 거절합니다. 만료된 선점은 먼저 정리합니다.
     */
    private Optional<BookingCreated> keepOrClear(List<BookingEntity> mine, long sessionId, List<Long> seatIds) {
        for (BookingEntity b : mine) {
            if (b.getStatus() == BookingEntity.Status.CONFIRMED && b.getSessionId() == sessionId) {
                throw new GeneralException(SeatErrorCode.SEAT_LIMIT_EXCEEDED, "이미 예매한 회차입니다").with("bookingId", b.getId());
            }
        }
        for (BookingEntity b : mine) {
            if (b.getStatus() != BookingEntity.Status.PENDING) continue;
            if (b.getPayingAt() != null || holdOpen(b)) {
                if (b.getSessionId() != sessionId || !new HashSet<>(seatsOf(b.getId())).equals(new HashSet<>(seatIds))) {
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
        }
        return Optional.empty();
    }

    /**
     * 결제 준비 - 같은 예매로 다시 불러도 같은 결과(Payment 멱등). 마지막 결제가 거절이면 Payment 가 새 주문번호를 줍니다 (80B).
     * 거절 뒤에는 먼저 이전 주문번호를 떼어 둡니다 - 준비가 실패해도 다음 N10 이 다시 준비하게.
     * Payment 불통: 새 선점(fresh)이면 선점을 풀고 503, 이어서 쓰는 선점이면 그대로 두고 503
     */
    private String prepared(long bookingId, long userId, int amount, boolean fresh) {
        if (!fresh) tx.executeWithoutResult(s -> bookingRepository.attachPgOrderId(bookingId, null));
        PaymentClient.Prepared prepared;
        try {
            prepared = paymentClient.prepare(bookingId, userId, amount);
        } catch (PaymentClient.Unavailable e) {
            log.warn("payment_prepare_failed bookingId={} error={}", bookingId, e.getMessage());
            if (fresh) release(bookingId, false);   // 결제 준비 전이라 돈이 나갈 수 없습니다 - 선점만 풀고 다시 시도하게
            throw paymentUnavailable("결제를 준비하지 못했습니다. 잠시 후 다시 시도해주세요");
        }
        tx.executeWithoutResult(s -> bookingRepository.attachPgOrderId(bookingId, prepared.pgOrderId()));
        return prepared.pgOrderId();
    }

    /** booking -> seat 순서 (확정, 반환과 같음) */
    private void stopPaying(long bookingId) {
        tx.executeWithoutResult(s -> {
            bookingRepository.clearPaying(bookingId);
            concertSeatRepository.stopPaying(bookingId);
        });
    }

    private static void rejectClosed(BookingEntity b) {
        if (b.getStatus() == BookingEntity.Status.CONFIRMED) throw new GeneralException(BookingErrorCode.ALREADY_PAID);
        if (b.getStatus() == BookingEntity.Status.CANCELLED) throw new GeneralException(BookingErrorCode.BOOKING_CANCELLED);
        if (b.getStatus() == BookingEntity.Status.EXPIRED) throw new GeneralException(BookingErrorCode.HOLD_EXPIRED);
    }

    /**
     * 예매 닫기 + 좌석 반환 - 예매 전이가 1행일 때만 좌석을 풉니다 (확보, 판매된 좌석만 바뀜).
     * 취소는 예매 행을 먼저 잠그고 상태를 봅니다 - 확정 예매였으면(환불) 같은 트랜잭션에서 booking.cancelled
     */
    private boolean release(long bookingId, boolean expired) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            String before = expired ? null : bookingRepository.lockStatus(bookingId);
            int closed = expired ? bookingRepository.expire(bookingId) : bookingRepository.cancel(bookingId);
            if (closed == 0) return false;
            int seats = concertSeatRepository.releaseBooking(bookingId);
            if (BookingEntity.Status.CONFIRMED.name().equals(before)) {
                BookingEntity b = bookingRepository.findById(bookingId).orElseThrow();
                ConcertEntity c = concertRepository.findById(b.getConcertId()).orElseThrow();
                events.bookingCancelled(b, c, sessionStartsAt(b), venueRepository.findById(c.getVenueId()).orElseThrow(), seats,
                        OffsetDateTime.now(clock));
            }
            log.info("booking_{} bookingId={} seats={}", expired ? "expired" : "cancelled", bookingId, seats);
            return true;
        }));
    }

    /** 결제 행이 없으면(준비 실패) NOT_FOUND - 돈이 나간 적이 없으니 그대로 진행합니다 */
    private String cancelPayment(long bookingId, String reason, boolean refundApproved) {
        try {
            return paymentClient.cancel(bookingId, reason, refundApproved);
        } catch (PaymentClient.Unavailable e) {
            log.warn("payment_cancel_failed bookingId={} error={}", bookingId, e.getMessage());
            throw paymentUnavailable("예매를 취소하지 못했습니다. 잠시 후 다시 시도해주세요");
        }
    }

    /** 예매가 잡은 공연 좌석들 (1인 N매) */
    private List<Long> seatsOf(long bookingId) {
        return bookingSeatRepository.findByBookingId(bookingId).stream().map(BookingSeatEntity::getConcertSeatId).toList();
    }

    private List<SeatRef> seatRefs(long bookingId) {
        return concertSeatRepository.findRefs(seatsOf(bookingId)).stream()
                .map(r -> new SeatRef(r.getId(), r.getFloor(), r.getSection(), r.getRowNo(), r.getSeatNo(), r.getPrice())).toList();
    }

    private BookingCreated created(BookingEntity b, String pgOrderId) {
        return new BookingCreated(b.getId(), b.getTotalPrice(), pgOrderId, "PENDING", expiresAt(b), expiresIn(b));
    }

    /** 화면 마감 = 서버 마감 - 유예 - 프론트 타이머가 0 이 되는 시각 */
    private OffsetDateTime expiresAt(BookingEntity b) {
        return b.getHoldExpiresAt().minusSeconds(props.holdGraceSeconds());
    }

    /** 화면 마감까지 남은 초 (DB 시계 기준) - 프론트는 이 값으로 타이머를 돌립니다 (기기 시계가 틀려도 맞음) */
    private long expiresIn(BookingEntity b) {
        return Math.max(0, Duration.between(dbNow(), expiresAt(b)).toSeconds());
    }

    /** 확정 예매 환불 마감 = 회차 시작 - cancel-deadline-hours (회원 취소, 탈퇴 환불 같은 기준) */
    private OffsetDateTime cancelDeadline(BookingEntity b) {
        return sessionStartsAt(b).minusHours(props.cancelDeadlineHours());
    }

    private OffsetDateTime sessionStartsAt(BookingEntity b) {
        return sessionRepository.findById(b.getSessionId()).orElseThrow().getStartsAt();
    }

    /** 서버 마감 전인가 (DB now()) */
    private boolean holdOpen(BookingEntity b) {
        return b.getHoldExpiresAt() != null && b.getHoldExpiresAt().isAfter(dbNow());
    }

    /** 지금부터 서버 마감까지 초 = 화면 시계(입장 토큰 남은 초, 최대 hold-minutes) + 유예. 대기열을 끈 공연은 hold-minutes 전체 */
    private long holdSeconds(OptionalLong entryLeft) {
        long screen = props.holdMinutes() * 60;
        return Math.min(screen, entryLeft.orElse(screen)) + props.holdGraceSeconds();
    }

    private OffsetDateTime dbNow() {
        return Instant.ofEpochMilli(concertSeatRepository.nowMillis()).atOffset(ZoneOffset.UTC);
    }

    private static GeneralException notCancellable(String message) {
        return new GeneralException(BookingErrorCode.BOOKING_NOT_CANCELLABLE, message);
    }

    private static GeneralException paymentUnavailable(String message) {
        return new GeneralException(BookingErrorCode.PAYMENT_UNAVAILABLE, message);
    }
}
