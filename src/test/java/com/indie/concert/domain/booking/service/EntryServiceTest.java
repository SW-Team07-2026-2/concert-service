package com.indie.concert.domain.booking.service;

import com.indie.concert.domain.booking.dto.response.CheckInResult;
import com.indie.concert.domain.booking.entity.BookingEntity;
import com.indie.concert.domain.booking.entity.BookingSeatEntity;
import com.indie.concert.domain.booking.repository.BookingRepository;
import com.indie.concert.domain.booking.repository.BookingSeatRepository;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.concert.repository.VenueRepository;
import com.indie.concert.domain.seat.entity.SeatEntity;
import com.indie.concert.domain.seat.repository.SeatRepository;
import com.indie.concert.global.common.exception.GeneralException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.indie.concert.domain.booking.service.EntryQrTokensTest.PROPS;
import static com.indie.concert.domain.booking.service.EntryQrTokensTest.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 입장 QR 은 결제된 내 예매 · 공연 3시간 전 ~ 시작 6시간 뒤에만, 검표는 같은 공연 · 한 번만 */
class EntryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final OffsetDateTime NOW_AT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final BookingSeatRepository bookingSeats = mock(BookingSeatRepository.class);
    private final SeatRepository seats = mock(SeatRepository.class);
    private final ConcertRepository concerts = mock(ConcertRepository.class);
    private final EntryService service = at(NOW);

    @Test
    void issuesQrFrom3HoursBeforeStart() {
        paid(NOW_AT.plusHours(3));

        String token = service.issue(3L, 30L).token();

        assertThat(EntryQrTokensTest.at(NOW).verify(token)).isEqualTo(new EntryQrTokens.Claims(30L, 42L));
    }

    @Test
    void earlierThan3HoursIsNotOpen() {
        paid(NOW_AT.plusHours(3).plusMinutes(1));

        assertThatThrownBy(() -> service.issue(3L, 30L))
                .isInstanceOf(GeneralException.class)
                .satisfies(e -> {
                    assertThat(((GeneralException) e).getErrorCode().getCode()).isEqualTo("ENTRY_NOT_OPEN");
                    assertThat(((GeneralException) e).getData()).containsEntry("opensAt", NOW_AT.plusMinutes(1));
                });
    }

    @Test
    void closesSixHoursAfterStart() {
        paid(NOW_AT.minusHours(6).minusMinutes(1));

        assertCode(() -> service.issue(3L, 30L), "ENTRY_CLOSED");
    }

    @Test
    void onlyPaidAndNotYetUsedBookings() {
        BookingEntity pending = booking(BookingEntity.Status.PENDING, null);
        when(bookings.findByIdAndUserId(30L, 3L)).thenReturn(Optional.of(pending));
        assertCode(() -> service.issue(3L, 30L), "ENTRY_NOT_AVAILABLE");

        BookingEntity used = booking(BookingEntity.Status.CONFIRMED, NOW_AT.minusMinutes(10));
        when(bookings.findByIdAndUserId(30L, 3L)).thenReturn(Optional.of(used));
        assertCode(() -> service.issue(3L, 30L), "ALREADY_CHECKED_IN");

        assertCode(() -> service.issue(4L, 31L), "NOT_FOUND");   // 남의 예매 · 없는 예매
    }

    @Test
    void checkInRecordsCheckerAndReturnsSeat() {
        when(bookings.checkIn(30L, 77L, NOW_AT)).thenReturn(1);
        when(bookingSeats.findByBookingId(30L)).thenReturn(List.of(new BookingSeatEntity(30L, 901L)));
        SeatEntity seat = mock(SeatEntity.class);
        when(seat.getId()).thenReturn(901L);
        when(seat.getSection()).thenReturn("A");
        when(seat.getRowNo()).thenReturn("1");
        when(seat.getSeatNo()).thenReturn(3);
        when(seat.getPrice()).thenReturn(55000);
        when(seats.findById(901L)).thenReturn(Optional.of(seat));

        CheckInResult result = service.checkIn(42L, qr(), 77L);

        assertThat(result.bookingId()).isEqualTo(30L);
        assertThat(result.seats()).singleElement().satisfies(s -> assertThat(s.section() + s.row() + "-" + s.number()).isEqualTo("A1-3"));
        assertThat(result.checkedInAt()).isEqualTo(NOW_AT);
    }

    /** ★ 같은 QR 을 두 번 — 두 번째는 409 · 언제 입장했는지 같이 */
    @Test
    void secondScanIsRejected() {
        when(bookings.checkIn(30L, 77L, NOW_AT)).thenReturn(0);
        BookingEntity used = booking(BookingEntity.Status.CONFIRMED, NOW_AT.minusSeconds(10));
        when(bookings.findById(30L)).thenReturn(Optional.of(used));

        assertThatThrownBy(() -> service.checkIn(42L, qr(), 77L))
                .isInstanceOf(GeneralException.class)
                .satisfies(e -> {
                    assertThat(((GeneralException) e).getErrorCode().getCode()).isEqualTo("ALREADY_CHECKED_IN");
                    assertThat(((GeneralException) e).getData()).containsEntry("checkedInAt", NOW_AT.minusSeconds(10));
                });
    }

    @Test
    void refundedAfterQrIsRejected() {
        when(bookings.checkIn(30L, 77L, NOW_AT)).thenReturn(0);
        BookingEntity cancelled = booking(BookingEntity.Status.CANCELLED, null);
        when(bookings.findById(30L)).thenReturn(Optional.of(cancelled));

        assertCode(() -> service.checkIn(42L, qr(), 77L), "ENTRY_NOT_AVAILABLE");
    }

    @Test
    void otherConcertsTicketIsRejectedWithoutRecording() {
        assertCode(() -> service.checkIn(43L, qr(), 77L), "WRONG_CONCERT");
        verify(bookings, never()).checkIn(anyLong(), anyLong(), any());
    }

    /** ★ 캡처본 — 31초 전 QR */
    @Test
    void screenshotIsRejectedWithoutRecording() {
        String old = EntryQrTokensTest.at(NOW.minusSeconds(31)).issue(30L, 42L).token();

        assertCode(() -> service.checkIn(42L, old, 77L), "ENTRY_QR_EXPIRED");
        verify(bookings, never()).checkIn(anyLong(), anyLong(), any());
    }

    private String qr() {
        return EntryQrTokensTest.at(NOW).issue(30L, 42L).token();
    }

    private void paid(OffsetDateTime startsAt) {
        BookingEntity b = booking(BookingEntity.Status.CONFIRMED, null);
        when(bookings.findByIdAndUserId(30L, 3L)).thenReturn(Optional.of(b));
        ConcertEntity c = mock(ConcertEntity.class);
        when(c.getId()).thenReturn(42L);
        when(c.getStartsAt()).thenReturn(startsAt);
        when(concerts.findById(42L)).thenReturn(Optional.of(c));
    }

    private static BookingEntity booking(BookingEntity.Status status, OffsetDateTime checkedInAt) {
        BookingEntity b = mock(BookingEntity.class);
        when(b.getId()).thenReturn(30L);
        when(b.getConcertId()).thenReturn(42L);
        when(b.getStatus()).thenReturn(status);
        when(b.getCheckedInAt()).thenReturn(checkedInAt);
        return b;
    }

    private EntryService at(Instant now) {
        return new EntryService(bookings, bookingSeats, seats, concerts, mock(VenueRepository.class),
                EntryQrTokensTest.at(now), PROPS, Clock.fixed(now, ZoneOffset.UTC));
    }
}
