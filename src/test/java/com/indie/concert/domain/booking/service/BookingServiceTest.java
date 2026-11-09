package com.indie.concert.domain.booking.service;

import com.indie.concert.domain.booking.client.PaymentClient;
import com.indie.concert.domain.booking.dto.event.BookingEvents;
import com.indie.concert.domain.booking.dto.response.BookingCreated;
import com.indie.concert.domain.booking.dto.response.BookingPaid;
import com.indie.concert.domain.booking.entity.BookingEntity;
import com.indie.concert.domain.booking.entity.BookingSeatEntity;
import com.indie.concert.domain.booking.repository.BookingRepository;
import com.indie.concert.domain.booking.repository.BookingSeatRepository;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.concert.repository.VenueRepository;
import com.indie.concert.domain.queue.service.QueueService;
import com.indie.concert.domain.seat.entity.SeatEntity;
import com.indie.concert.domain.seat.repository.SeatRepository;
import com.indie.concert.global.common.exception.GeneralException;
import com.indie.concert.global.config.ConcertProperties;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Saga 보상 순서 — 좌석을 푸는 모든 경로는 Payment 를 먼저 끝냅니다.
 * 좌석 선점 (11/04 77A) — 결제 승인은 내 선점을 PAYING 으로 바꾼 뒤에만. 동시성 · DB 시계는 SeatHoldFlowTest (실제 PostgreSQL)
 */
class BookingServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final OffsetDateTime NOW_AT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final BookingSeatRepository bookingSeats = mock(BookingSeatRepository.class);
    private final SeatRepository seats = mock(SeatRepository.class);
    private final ConcertRepository concerts = mock(ConcertRepository.class);
    private final PaymentClient payment = mock(PaymentClient.class);
    private final QueueService queue = mock(QueueService.class);
    private final BookingService service = new BookingService(bookings, bookingSeats, seats, concerts,
            mock(VenueRepository.class), queue, payment, mock(BookingEvents.class),
            new TransactionTemplate(mock(PlatformTransactionManager.class)),
            new ConcertProperties(null, null, null, "Asia/Seoul", new ConcertProperties.Booking(7, 179, 60, 24, 30000),
                    null, null, null, null, null, null),
            Clock.fixed(NOW, ZoneOffset.UTC));

    {
        when(seats.nowMillis()).thenReturn(NOW.toEpochMilli());   // DB 시계
    }

    /** 선점 마감이 지난 예매 — 결제부터 무효화하고 좌석을 풉니다 */
    @Test
    void expiryCancelsPaymentBeforeReleasingSeat() {
        when(bookings.findExpiredHolds(50)).thenReturn(List.of(5L));
        when(payment.cancel(5L, "EXPIRED", false)).thenReturn("CANCELLED");
        when(bookings.expire(5L)).thenReturn(1);

        service.expirePending(50);

        InOrder order = inOrder(payment, bookings, seats);
        order.verify(payment).cancel(5L, "EXPIRED", false);
        order.verify(bookings).expire(5L);
        order.verify(seats).releaseBooking(5L);
    }

    /** 정리 직전에 결제가 승인됐다면 확정합니다 (SOLD) — 좌석을 풀지 않음 */
    @Test
    void expiryConfirmsBookingPaidInTheMeantime() {
        when(bookings.findExpiredHolds(50)).thenReturn(List.of(5L));
        when(payment.cancel(5L, "EXPIRED", false)).thenReturn("APPROVED");

        service.expirePending(50);

        verify(bookings).confirm(5L);
        verify(bookings, never()).expire(anyLong());
        verify(seats, never()).releaseBooking(anyLong());
    }

    @Test
    void expiryPausesWhilePaymentIsDown() {
        when(bookings.findExpiredHolds(50)).thenReturn(List.of(5L, 6L));
        when(payment.cancel(anyLong(), anyString(), anyBoolean())).thenThrow(new PaymentClient.Unavailable("cancel", new RuntimeException("down")));

        service.expirePending(50);

        verify(payment, times(1)).cancel(anyLong(), anyString(), anyBoolean());
        verify(seats, never()).releaseBooking(anyLong());
    }

    /** 81A — 응답 없는 PAYING 은 1분 뒤 건마다 정리. 한 건이 토스 처리 중(503)이어도 다음 건은 진행 */
    @Test
    void stalePayingIsSettledOneByOne() {
        when(bookings.findStalePaying(60, 50)).thenReturn(List.of(5L, 6L));
        when(payment.cancel(5L, "EXPIRED", false)).thenThrow(new PaymentClient.Unavailable("cancel", new RuntimeException("processing")));
        when(payment.cancel(6L, "EXPIRED", false)).thenReturn("CANCELLED");
        when(bookings.expire(6L)).thenReturn(1);

        service.expirePending(50);

        verify(seats, never()).releaseBooking(5L);
        verify(seats).releaseBooking(6L);
    }

    @Test
    void confirmedBookingIsNotCancellableWithin24Hours() {
        BookingEntity booking = mock(BookingEntity.class);
        when(booking.getStatus()).thenReturn(BookingEntity.Status.CONFIRMED);
        when(booking.getConcertId()).thenReturn(1L);
        ConcertEntity concert = mock(ConcertEntity.class);
        when(concert.getStartsAt()).thenReturn(NOW_AT.plusHours(23));
        when(bookings.findByIdAndUserId(9L, 3L)).thenReturn(Optional.of(booking));
        when(concerts.findById(1L)).thenReturn(Optional.of(concert));

        assertThatThrownBy(() -> service.cancel(3L, 9L))
                .isInstanceOf(GeneralException.class)
                .extracting(e -> ((GeneralException) e).getErrorCode().getCode()).isEqualTo("BOOKING_NOT_CANCELLABLE");
        verify(payment, never()).cancel(anyLong(), anyString(), anyBoolean());
    }

    /** 입장한 예매는 환불 없음 — 취소 마감을 줄이는 설정 변경에도 */
    @Test
    void checkedInBookingIsNotCancellable() {
        BookingEntity booking = mock(BookingEntity.class);
        when(booking.getStatus()).thenReturn(BookingEntity.Status.CONFIRMED);
        when(booking.getCheckedInAt()).thenReturn(NOW_AT.minusMinutes(5));
        when(bookings.findByIdAndUserId(9L, 3L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.cancel(3L, 9L))
                .isInstanceOf(GeneralException.class)
                .extracting(e -> ((GeneralException) e).getErrorCode().getCode()).isEqualTo("BOOKING_NOT_CANCELLABLE");
        verify(payment, never()).cancel(anyLong(), anyString(), anyBoolean());
    }

    /** 결제 준비 실패 — 돈이 나갈 수 없으니 방금 잡은 선점을 풀고 503 */
    @Test
    void prepareFailureReleasesHold() {
        selectable(77L);
        newHold(9L);
        when(payment.prepare(9L, 3L, 55000)).thenThrow(new PaymentClient.Unavailable("prepare", new RuntimeException("down")));
        when(bookings.cancel(9L)).thenReturn(1);

        assertThatThrownBy(() -> service.create(1L, 3L, "token", 77L))
                .isInstanceOf(GeneralException.class)
                .extracting(e -> ((GeneralException) e).getErrorCode().getStatus().value()).isEqualTo(503);
        InOrder order = inOrder(bookings, seats);
        order.verify(bookings).cancel(9L);
        order.verify(seats).releaseBooking(9L);
    }

    /** 남이 선점한 좌석 — 바로 409 SEAT_TAKEN (결제창까지 가지 않음) */
    @Test
    void takenSeatIsRejectedAtOnce() {
        selectable(77L);
        when(bookings.findActive(1L, 3L)).thenReturn(Optional.empty());
        BookingEntity saved = pending(9L, NOW_AT.plusMinutes(9));
        when(bookings.save(any())).thenReturn(saved);
        when(seats.hold(77L, 1L, 9L, 3L)).thenReturn(0);

        assertThatThrownBy(() -> service.create(1L, 3L, "token", 77L))
                .isInstanceOf(GeneralException.class)
                .extracting(e -> ((GeneralException) e).getErrorCode().getCode()).isEqualTo("SEAT_TAKEN");
        verify(payment, never()).prepare(anyLong(), anyLong(), anyInt());
    }

    /** 내 선점이 다른 좌석에 살아 있으면 409 HOLD_EXISTS + 내 bookingId — 이전 선택을 자동으로 버리지 않음 */
    @Test
    void otherSeatWhileHoldingIsHoldExists() {
        selectable(77L);
        BookingEntity mine = pending(8L, NOW_AT.plusMinutes(5));
        when(bookings.findActive(1L, 3L)).thenReturn(Optional.of(mine));
        when(bookingSeats.findByBookingId(8L)).thenReturn(List.of(new BookingSeatEntity(8L, 76L)));

        assertThatThrownBy(() -> service.create(1L, 3L, "token", 77L))
                .isInstanceOfSatisfying(GeneralException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo("HOLD_EXISTS");
                    assertThat(e.getData()).containsEntry("bookingId", 8L);
                });
        verify(payment, never()).cancel(anyLong(), anyString(), anyBoolean());
        verify(bookings, never()).save(any());
    }

    /** ★ 선점 → PAYING → 승인 → 확정 순서. 승인은 PAYING 으로 바꾼 뒤에만 */
    @Test
    void payMovesSeatToPayingBeforeApproving() {
        payable(9L, 77L, NOW_AT.plusMinutes(5));
        when(seats.startPaying(9L, 3L)).thenReturn(1);
        when(payment.confirm(9L, 3L, "pk-9", 55000, null)).thenReturn(new PaymentClient.Confirmed("APPROVED", null));
        when(bookings.confirm(9L)).thenReturn(1);
        when(seats.sell(9L)).thenReturn(1);
        when(concerts.findById(1L)).thenReturn(Optional.of(mock(ConcertEntity.class)));

        BookingPaid paid = service.pay(3L, 9L, "pk-9", 55000, null);

        assertThat(paid.status()).isEqualTo("CONFIRMED");
        InOrder order = inOrder(seats, bookings, payment);
        order.verify(seats).startPaying(9L, 3L);
        order.verify(bookings).markPaying(9L);
        order.verify(payment).confirm(9L, 3L, "pk-9", 55000, null);
        order.verify(bookings).confirm(9L);
        order.verify(seats).sell(9L);
    }

    /** 선점 마감 뒤 · 남이 가져간 뒤 — 410 HOLD_EXPIRED, 토스(Payment 승인)는 부르지 않음 */
    @Test
    void expiredHoldCannotPay() {
        payable(9L, 77L, NOW_AT.minusSeconds(1));
        when(seats.startPaying(9L, 3L)).thenReturn(0);

        assertThatThrownBy(() -> service.pay(3L, 9L, "pk-9", 55000, null))
                .isInstanceOfSatisfying(GeneralException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo("HOLD_EXPIRED");
                    assertThat(e.getErrorCode().getStatus()).isEqualTo(HttpStatus.GONE);
                });
        verify(payment, never()).confirm(anyLong(), anyLong(), anyString(), anyInt(), any());
    }

    /** 같은 예매의 결제 승인 연타 — 이미 PAYING 이면 409 SEAT_LOCKED */
    @Test
    void payingTwiceIsSeatLocked() {
        BookingEntity b = payable(9L, 77L, NOW_AT.plusMinutes(5));
        when(b.getPayingAt()).thenReturn(NOW_AT.minusSeconds(2));
        when(seats.startPaying(9L, 3L)).thenReturn(0);

        assertThatThrownBy(() -> service.pay(3L, 9L, "pk-9", 55000, null))
                .extracting(e -> ((GeneralException) e).getErrorCode().getCode()).isEqualTo("SEAT_LOCKED");
        verify(payment, never()).confirm(anyLong(), anyLong(), anyString(), anyInt(), any());
    }

    /** ★ 80B — 거절(한도초과 등)이면 선점 유지 · PAYING → HELD · 새 결제(새 pgOrderId)로 다시 */
    @Test
    void declinedPaymentKeepsHoldWithNewOrder() {
        payable(9L, 77L, NOW_AT.plusMinutes(3));
        when(seats.startPaying(9L, 3L)).thenReturn(1);
        when(payment.confirm(9L, 3L, "pk-9", 55000, "REJECT_CARD_PAYMENT"))
                .thenReturn(new PaymentClient.Confirmed("DECLINED", "REJECT_CARD_PAYMENT"));
        when(payment.prepare(9L, 3L, 55000)).thenReturn(new PaymentClient.Prepared("TICKET-9b", "PENDING", null));

        BookingPaid paid = service.pay(3L, 9L, "pk-9", 55000, "REJECT_CARD_PAYMENT");

        assertThat(paid.status()).isEqualTo("PENDING");
        assertThat(paid.reason()).isEqualTo("REJECT_CARD_PAYMENT");
        assertThat(paid.pgOrderId()).isEqualTo("TICKET-9b");
        InOrder order = inOrder(seats, bookings, payment);
        order.verify(seats).stopPaying(9L);
        order.verify(bookings).clearPaying(9L);
        order.verify(bookings).attachPgOrderId(9L, null);
        order.verify(payment).prepare(9L, 3L, 55000);
        order.verify(bookings).attachPgOrderId(9L, "TICKET-9b");
        verify(seats, never()).releaseBooking(anyLong());
        verify(bookings, never()).cancel(anyLong());
    }

    /** 거절됐는데 선점 마감도 지났으면 바로 반환 (거절된 결제라 돈이 나갈 수 없음) */
    @Test
    void declinedAfterDeadlineExpiresHold() {
        payable(9L, 77L, NOW_AT.minusSeconds(1));
        when(seats.startPaying(9L, 3L)).thenReturn(1);
        when(payment.confirm(9L, 3L, "pk-9", 55000, null)).thenReturn(new PaymentClient.Confirmed("DECLINED", "REJECT_CARD_PAYMENT"));
        when(bookings.expire(9L)).thenReturn(1);

        assertThat(service.pay(3L, 9L, "pk-9", 55000, null).status()).isEqualTo("EXPIRED");
        verify(seats).releaseBooking(9L);
        verify(payment, never()).prepare(anyLong(), anyLong(), anyInt());
    }

    /** Payment 가 승인 전에 거부 (금액 위조 등) — PAYING → HELD 로 되돌리고 코드는 그대로 전달 */
    @Test
    void rejectedPaymentGoesBackToHeld() {
        payable(9L, 77L, NOW_AT.plusMinutes(5));
        when(seats.startPaying(9L, 3L)).thenReturn(1);
        when(payment.confirm(9L, 3L, "pk-9", 1000, null))
                .thenThrow(new PaymentClient.Rejected(HttpStatus.BAD_REQUEST, "AMOUNT_MISMATCH", "결제 금액이 일치하지 않습니다"));

        assertThatThrownBy(() -> service.pay(3L, 9L, "pk-9", 1000, null))
                .extracting(e -> ((GeneralException) e).getErrorCode().getCode()).isEqualTo("AMOUNT_MISMATCH");
        verify(seats).stopPaying(9L);
        verify(bookings, never()).cancel(anyLong());
    }

    /** 승인 응답 없음 — PAYING 그대로 두고 503 PAYMENT_PENDING (정리가 1분 뒤 결과를 물음) */
    @Test
    void unknownPaymentKeepsPaying() {
        payable(9L, 77L, NOW_AT.plusMinutes(5));
        when(seats.startPaying(9L, 3L)).thenReturn(1);
        when(payment.confirm(9L, 3L, "pk-9", 55000, null)).thenThrow(new PaymentClient.Unavailable("confirm", new RuntimeException("timeout")));

        assertThatThrownBy(() -> service.pay(3L, 9L, "pk-9", 55000, null))
                .isInstanceOfSatisfying(GeneralException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo("PAYMENT_PENDING");
                    assertThat(e.getErrorCode().getStatus().value()).isEqualTo(503);
                });
        verify(seats, never()).stopPaying(anyLong());
        verify(seats, never()).releaseBooking(anyLong());
    }

    /** 응답의 expiresIn(남은 초 · DB 시계)으로 프론트가 타이머를 돌립니다 — 대기열을 끈 공연은 화면 7분 · 서버 599초 */
    @Test
    void newHoldReturnsTimerInSeconds() {
        selectable(77L);
        newHold(9L);
        when(payment.prepare(9L, 3L, 55000)).thenReturn(new PaymentClient.Prepared("TICKET-9", "PENDING", null));

        BookingCreated created = service.create(1L, 3L, "token", 77L);

        verify(bookings).startHold(9L, 599);
        assertThat(created.expiresAt()).isEqualTo(NOW_AT.plusMinutes(7));
        assertThat(created.expiresIn()).isEqualTo(420);
        assertThat(created.pgOrderId()).isEqualTo("TICKET-9");
    }

    /** 좌석을 고를 때 입장 토큰에 남은 시간을 이어받습니다 — 시계가 다시 시작되지 않음 */
    @Test
    void holdContinuesEntryTokenClock() {
        when(queue.requireEntry(any(), anyLong(), any())).thenReturn(OptionalLong.of(300));
        selectable(77L);
        newHold(9L);
        when(payment.prepare(9L, 3L, 55000)).thenReturn(new PaymentClient.Prepared("TICKET-9", "PENDING", null));

        service.create(1L, 3L, "token", 77L);

        verify(bookings).startHold(9L, 479);
    }

    /** 토큰이 화면 시간보다 길어도 (배포 전에 받은 10분 토큰) 서버 마감은 토스 10분을 넘지 않음 */
    @Test
    void holdNeverExceedsScreenTime() {
        when(queue.requireEntry(any(), anyLong(), any())).thenReturn(OptionalLong.of(600));
        selectable(77L);
        newHold(9L);
        when(payment.prepare(9L, 3L, 55000)).thenReturn(new PaymentClient.Prepared("TICKET-9", "PENDING", null));

        service.create(1L, 3L, "token", 77L);

        verify(bookings).startHold(9L, 599);
    }

    /** 같은 좌석을 다시 골라도 타이머는 그대로 — 새로고침 · 더블클릭으로 늘릴 수 없음 */
    @Test
    void sameSeatKeepsTimer() {
        selectable(77L);
        BookingEntity running = pending(8L, NOW_AT.plusSeconds(479));   // 서버 마감까지 479초 = 화면 5분
        when(running.getPgOrderId()).thenReturn("TICKET-8");
        when(bookings.findActive(1L, 3L)).thenReturn(Optional.of(running));
        when(bookingSeats.findByBookingId(8L)).thenReturn(List.of(new BookingSeatEntity(8L, 77L)));

        BookingCreated again = service.create(1L, 3L, "token", 77L);

        assertThat(again.bookingId()).isEqualTo(8L);
        assertThat(again.expiresIn()).isEqualTo(300);
        verify(bookings, never()).save(any());
        verify(payment, never()).prepare(anyLong(), anyLong(), anyInt());
    }

    /** 내 선점이 마감됐는데 정리 전 — 정리를 기다리지 않고 먼저 정리(결제 무효화 → EXPIRED)한 뒤 새로 선점 */
    @Test
    void expiredOwnHoldIsClearedBeforeNewHold() {
        selectable(77L);
        BookingEntity ended = pending(8L, NOW_AT.minusSeconds(10));
        when(bookings.findActive(1L, 3L)).thenReturn(Optional.of(ended));
        when(payment.cancel(8L, "EXPIRED", false)).thenReturn("CANCELLED");
        when(bookings.expire(8L)).thenReturn(1);
        newHold(9L);
        when(payment.prepare(9L, 3L, 55000)).thenReturn(new PaymentClient.Prepared("TICKET-9", "PENDING", null));

        assertThat(service.create(1L, 3L, "token", 77L).bookingId()).isEqualTo(9L);

        InOrder order = inOrder(payment, bookings, seats);
        order.verify(payment).cancel(8L, "EXPIRED", false);
        order.verify(bookings).expire(8L);
        order.verify(seats).releaseBooking(8L);
        order.verify(bookings).save(any());
    }

    /** 정리된 선점은 HOLD_EXPIRED, 취소된 예매는 BOOKING_CANCELLED */
    @Test
    void closedBookingsCannotPayWithOwnCodes() {
        BookingEntity expired = pending(9L, NOW_AT);
        when(expired.getStatus()).thenReturn(BookingEntity.Status.EXPIRED);
        BookingEntity cancelled = pending(10L, NOW_AT);
        when(cancelled.getStatus()).thenReturn(BookingEntity.Status.CANCELLED);
        when(bookings.findByIdAndUserId(9L, 3L)).thenReturn(Optional.of(expired));
        when(bookings.findByIdAndUserId(10L, 3L)).thenReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> service.pay(3L, 9L, "pk-9", 55000, null))
                .extracting(e -> ((GeneralException) e).getErrorCode().getCode()).isEqualTo("HOLD_EXPIRED");
        assertThatThrownBy(() -> service.pay(3L, 10L, "pk-10", 55000, null))
                .extracting(e -> ((GeneralException) e).getErrorCode().getCode()).isEqualTo("BOOKING_CANCELLED");
        verify(seats, never()).startPaying(anyLong(), anyLong());
    }

    private void selectable(long seatId) {
        ConcertEntity concert = mock(ConcertEntity.class);
        when(concert.getId()).thenReturn(1L);
        when(concerts.findById(1L)).thenReturn(Optional.of(concert));
        SeatEntity seat = mock(SeatEntity.class);
        when(seat.getConcertId()).thenReturn(1L);
        when(seat.getPrice()).thenReturn(55000);
        when(seat.getStatus()).thenReturn(SeatEntity.Status.AVAILABLE);
        when(seats.findById(seatId)).thenReturn(Optional.of(seat));
    }

    /** 새 선점 — 저장 · 선점 성공 · 다시 읽으면 서버 마감 = 지금 + 599초 */
    private void newHold(long bookingId) {
        BookingEntity saved = pending(bookingId, NOW_AT.plusSeconds(599));
        when(bookings.save(any())).thenReturn(saved);
        when(bookings.findById(bookingId)).thenReturn(Optional.of(saved));
        when(seats.hold(77L, 1L, bookingId, 3L)).thenReturn(1);
    }

    private BookingEntity payable(long bookingId, long seatId, OffsetDateTime holdExpiresAt) {
        BookingEntity b = pending(bookingId, holdExpiresAt);
        when(b.getConcertId()).thenReturn(1L);
        when(bookings.findByIdAndUserId(bookingId, 3L)).thenReturn(Optional.of(b));
        when(bookings.findById(bookingId)).thenReturn(Optional.of(b));
        when(bookingSeats.findByBookingId(bookingId)).thenReturn(List.of(new BookingSeatEntity(bookingId, seatId)));
        return b;
    }

    private static BookingEntity pending(long id, OffsetDateTime holdExpiresAt) {
        BookingEntity b = mock(BookingEntity.class);
        when(b.getId()).thenReturn(id);
        when(b.getUserId()).thenReturn(3L);
        when(b.getStatus()).thenReturn(BookingEntity.Status.PENDING);
        when(b.getTotalPrice()).thenReturn(55000);
        when(b.getCreatedAt()).thenReturn(NOW_AT);
        when(b.getHoldExpiresAt()).thenReturn(holdExpiresAt);
        return b;
    }
}
