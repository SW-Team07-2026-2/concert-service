package com.indie.concert.domain.booking.service;

import com.indie.concert.domain.booking.client.PaymentClient;
import com.indie.concert.domain.booking.dto.event.BookingEvents;
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
import com.indie.concert.domain.seat.repository.SeatLock;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Saga 보상 순서 — 좌석을 푸는 모든 경로는 Payment 를 먼저 끝냅니다. 좌석 경합 — 좌석을 확보한 뒤에만 결제를 승인합니다 */
class BookingServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final OffsetDateTime NOW_AT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final BookingSeatRepository bookingSeats = mock(BookingSeatRepository.class);
    private final SeatRepository seats = mock(SeatRepository.class);
    private final ConcertRepository concerts = mock(ConcertRepository.class);
    private final PaymentClient payment = mock(PaymentClient.class);
    private final SeatLock lock = mock(SeatLock.class);
    private final BookingService service = new BookingService(bookings, bookingSeats, seats, concerts,
            mock(VenueRepository.class), mock(QueueService.class), payment, lock, mock(BookingEvents.class),
            new TransactionTemplate(mock(PlatformTransactionManager.class)),
            new ConcertProperties(null, null, null, "Asia/Seoul", new ConcertProperties.Booking(3, 30, 24, 30000, 3000),
                    null, null, null, null, null, null),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void expiryCancelsPaymentBeforeReleasingSeat() {
        when(bookings.findExpiredPending(any(), any(), eq(50))).thenReturn(List.of(5L));
        when(payment.cancel(5L, "EXPIRED", false)).thenReturn("CANCELLED");
        when(bookings.expire(5L)).thenReturn(1);

        service.expirePending(50);

        InOrder order = inOrder(payment, bookings, seats);
        order.verify(payment).cancel(5L, "EXPIRED", false);
        order.verify(bookings).expire(5L);
        order.verify(seats).releaseBooking(5L);
    }

    /** 만료 직전에 결제가 승인됐다면 건드리지 않습니다 — payment.completed 가 곧 확정합니다 */
    @Test
    void expirySkipsBookingPaidInTheMeantime() {
        when(bookings.findExpiredPending(any(), any(), eq(50))).thenReturn(List.of(5L));
        when(payment.cancel(5L, "EXPIRED", false)).thenReturn("APPROVED");

        service.expirePending(50);

        verify(bookings, never()).expire(anyLong());
        verify(seats, never()).releaseBooking(anyLong());
    }

    @Test
    void expiryPausesWhilePaymentIsDown() {
        when(bookings.findExpiredPending(any(), any(), eq(50))).thenReturn(List.of(5L, 6L));
        when(payment.cancel(anyLong(), anyString(), anyBoolean())).thenThrow(new PaymentClient.Unavailable("cancel", new RuntimeException("down")));

        service.expirePending(50);

        verify(payment, times(1)).cancel(anyLong(), anyString(), anyBoolean());
        verify(seats, never()).releaseBooking(anyLong());
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

    /** 결제 준비 실패 — 예매만 닫습니다 (좌석은 고를 때 잡지 않음) */
    @Test
    void prepareFailureCancelsBooking() {
        selectable(77L);
        when(bookings.findActive(1L, 3L)).thenReturn(Optional.empty());
        BookingEntity saved = pending(9L, NOW_AT);
        when(bookings.save(any())).thenReturn(saved);
        when(payment.prepare(9L, 3L, 55000)).thenThrow(new PaymentClient.Unavailable("prepare", new RuntimeException("down")));
        when(bookings.cancel(9L)).thenReturn(1);

        assertThatThrownBy(() -> service.create(1L, 3L, "token", 77L))
                .isInstanceOf(GeneralException.class)
                .extracting(e -> ((GeneralException) e).getErrorCode().getStatus().value()).isEqualTo(503);
        verify(bookings).cancel(9L);
        verify(seats, never()).claim(anyLong(), anyLong(), anyLong(), any());
    }

    /** 다른 좌석을 고르면 이전 선택의 결제부터 무효화하고 새 예매를 만듭니다 */
    @Test
    void pickingAnotherSeatDropsPreviousSelectionFirst() {
        selectable(77L);
        BookingEntity previous = pending(8L, NOW_AT.minusMinutes(1));
        when(previous.getPgOrderId()).thenReturn("TICKET-8");
        when(bookings.findActive(1L, 3L)).thenReturn(Optional.of(previous));
        when(bookingSeats.findByBookingId(8L)).thenReturn(List.of(new BookingSeatEntity(8L, 76L)));
        when(payment.cancel(8L, "SEAT_CHANGED", false)).thenReturn("CANCELLED");
        when(bookings.cancel(8L)).thenReturn(1);
        BookingEntity saved = pending(9L, NOW_AT);
        when(bookings.save(any())).thenReturn(saved);
        when(payment.prepare(9L, 3L, 55000)).thenReturn(new PaymentClient.Prepared("TICKET-9", "PENDING", null));

        assertThat(service.create(1L, 3L, "token", 77L).bookingId()).isEqualTo(9L);

        InOrder order = inOrder(payment, bookings, seats);
        order.verify(payment).cancel(8L, "SEAT_CHANGED", false);
        order.verify(bookings).cancel(8L);
        order.verify(seats).releaseBooking(8L);
        order.verify(bookings).save(any());
    }

    /** ★ 확보 → 승인 → 확정 순서. 승인은 좌석을 확보한 뒤에만 */
    @Test
    void payClaimsSeatBeforeApproving() {
        payable(9L, 77L);
        when(seats.claim(eq(77L), eq(9L), eq(3L), any())).thenReturn(1);
        when(payment.confirm(9L, 3L, "pk-9", 55000, null)).thenReturn(new PaymentClient.Confirmed("APPROVED", null));
        when(bookings.confirm(9L)).thenReturn(1);
        when(concerts.findById(1L)).thenReturn(Optional.of(mock(ConcertEntity.class)));

        BookingPaid paid = service.pay(3L, 9L, "pk-9", 55000, null);

        assertThat(paid.status()).isEqualTo("CONFIRMED");
        InOrder order = inOrder(seats, payment, bookings);
        order.verify(seats).claim(eq(77L), eq(9L), eq(3L), eq(NOW_AT.plusSeconds(30)));
        order.verify(payment).confirm(9L, 3L, "pk-9", 55000, null);
        order.verify(bookings).confirm(9L);
        order.verify(seats).sell(9L);
    }

    /** 같은 좌석을 고른 다른 사람이 먼저 확보 — 돈이 나가기 전에 409 "이미 선택된 좌석입니다" */
    @Test
    void loserIsBlockedBeforePayment() {
        payable(9L, 77L);
        when(seats.claim(eq(77L), eq(9L), eq(3L), any())).thenReturn(0);
        SeatEntity taken = mock(SeatEntity.class);
        when(taken.getBookingId()).thenReturn(10L);
        when(seats.findById(77L)).thenReturn(Optional.of(taken));

        assertThatThrownBy(() -> service.pay(3L, 9L, "pk-9", 55000, null))
                .isInstanceOf(GeneralException.class)
                .extracting(e -> ((GeneralException) e).getErrorCode().getCode()).isEqualTo("SEAT_UNAVAILABLE");
        verify(payment, never()).confirm(anyLong(), anyLong(), anyString(), anyInt(), any());
    }

    /** 결제 거절 — 예매를 닫고 좌석을 다시 경합으로 */
    @Test
    void declinedPaymentReleasesSeat() {
        payable(9L, 77L);
        when(seats.claim(eq(77L), eq(9L), eq(3L), any())).thenReturn(1);
        when(payment.confirm(9L, 3L, "pk-9", 55000, "CARD_LIMIT")).thenReturn(new PaymentClient.Confirmed("DECLINED", "CARD_LIMIT"));
        when(bookings.cancel(9L)).thenReturn(1);

        BookingPaid paid = service.pay(3L, 9L, "pk-9", 55000, "CARD_LIMIT");

        assertThat(paid.status()).isEqualTo("CANCELLED");
        verify(seats).releaseBooking(9L);
        verify(bookings, never()).confirm(anyLong());
    }

    /** Payment 가 승인 전에 거부 (금액 위조 등) — 확보만 풀고 코드는 그대로 전달 */
    @Test
    void rejectedPaymentUnclaimsSeat() {
        payable(9L, 77L);
        when(seats.claim(eq(77L), eq(9L), eq(3L), any())).thenReturn(1);
        when(payment.confirm(9L, 3L, "pk-9", 1000, null))
                .thenThrow(new PaymentClient.Rejected(HttpStatus.BAD_REQUEST, "AMOUNT_MISMATCH", "결제 금액이 일치하지 않습니다"));

        assertThatThrownBy(() -> service.pay(3L, 9L, "pk-9", 1000, null))
                .isInstanceOf(GeneralException.class)
                .extracting(e -> ((GeneralException) e).getErrorCode().getCode()).isEqualTo("AMOUNT_MISMATCH");
        verify(seats).unclaim(9L);
        verify(bookings, never()).cancel(anyLong());
    }

    @Test
    void expiredSelectionCannotPay() {
        BookingEntity b = pending(9L, NOW_AT.minusMinutes(4));
        when(bookings.findByIdAndUserId(9L, 3L)).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.pay(3L, 9L, "pk-9", 55000, null))
                .isInstanceOf(GeneralException.class)
                .extracting(e -> ((GeneralException) e).getErrorCode().getCode()).isEqualTo("SELECTION_EXPIRED");
        verify(seats, never()).claim(anyLong(), anyLong(), anyLong(), any());
    }

    private void selectable(long seatId) {
        ConcertEntity concert = mock(ConcertEntity.class);
        when(concert.getId()).thenReturn(1L);
        when(concerts.findById(1L)).thenReturn(Optional.of(concert));
        when(lock.tryLock(anyString(), any())).thenReturn(() -> { });
        SeatEntity seat = mock(SeatEntity.class);
        when(seat.getConcertId()).thenReturn(1L);
        when(seat.getPrice()).thenReturn(55000);
        when(seat.getStatus()).thenReturn(SeatEntity.Status.AVAILABLE);
        when(seats.findById(seatId)).thenReturn(Optional.of(seat));
    }

    private void payable(long bookingId, long seatId) {
        BookingEntity b = pending(bookingId, NOW_AT.minusMinutes(1));
        when(b.getConcertId()).thenReturn(1L);
        when(bookings.findByIdAndUserId(bookingId, 3L)).thenReturn(Optional.of(b));
        when(bookings.findById(bookingId)).thenReturn(Optional.of(b));
        when(bookingSeats.findByBookingId(bookingId)).thenReturn(List.of(new BookingSeatEntity(bookingId, seatId)));
    }

    private static BookingEntity pending(long id, OffsetDateTime createdAt) {
        BookingEntity b = mock(BookingEntity.class);
        when(b.getId()).thenReturn(id);
        when(b.getStatus()).thenReturn(BookingEntity.Status.PENDING);
        when(b.getTotalPrice()).thenReturn(55000);
        when(b.getCreatedAt()).thenReturn(createdAt);
        return b;
    }
}
