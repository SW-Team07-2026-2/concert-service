package com.indie.concert.domain.booking.service;

import com.indie.concert.domain.booking.dto.response.CheckInResult;
import com.indie.concert.domain.booking.dto.response.ConcertRef;
import com.indie.concert.domain.booking.entity.BookingEntity;
import com.indie.concert.domain.booking.entity.BookingSeatEntity;
import com.indie.concert.domain.booking.repository.BookingRepository;
import com.indie.concert.domain.booking.repository.BookingSeatRepository;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.ConcertSessionEntity;
import com.indie.concert.domain.concert.entity.VenueEntity;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.concert.repository.ConcertSessionRepository;
import com.indie.concert.domain.concert.repository.VenueRepository;
import com.indie.concert.domain.seat.repository.ConcertSeatRepository;
import com.indie.concert.global.common.exception.GeneralException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.indie.concert.domain.booking.service.EntryQrTokensTest.PROPS;
import static com.indie.concert.domain.booking.service.EntryQrTokensTest.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 11/27 입장 시간은 예매한 회차 기준 [시작 − 60분, 시작 + 30분) — QR 발급 · 검표 모두.
 * 입장 QR 은 결제된 내 예매 · 탈퇴 정리 전에만, 검표는 같은 공연 · 한 번만 · 순서는 INVALID → WRONG_CONCERT → 시간 → 만료
 */
class EntryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final OffsetDateTime NOW_AT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final long SESSION = 500L;

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final BookingSeatRepository bookingSeats = mock(BookingSeatRepository.class);
    private final ConcertSeatRepository seats = mock(ConcertSeatRepository.class);
    private final ConcertRepository concerts = mock(ConcertRepository.class);
    private final ConcertSessionRepository sessions = mock(ConcertSessionRepository.class);
    private final VenueRepository venues = mock(VenueRepository.class);
    private final EntryService service = at(NOW);

    @Test
    void issuesQrFrom60MinutesBeforeSessionStart() {
        paid(NOW_AT.plusMinutes(60));

        String token = service.issue(3L, 30L).token();

        assertThat(EntryQrTokensTest.at(NOW).parse(token)).isEqualTo(new EntryQrTokens.Claims(30L, 42L, NOW.plusSeconds(30)));
    }

    @Test
    void earlierThan60MinutesIsNotOpen() {
        paid(NOW_AT.plusMinutes(61));

        assertThatThrownBy(() -> service.issue(3L, 30L))
                .isInstanceOf(GeneralException.class)
                .satisfies(e -> {
                    assertThat(((GeneralException) e).getErrorCode().getCode()).isEqualTo("ENTRY_NOT_OPEN");
                    assertThat(((GeneralException) e).getData()).containsEntry("opensAt", NOW_AT.plusMinutes(1));
                });
    }

    /** 입장 마감 = 회차 시작 30분 뒤 (공연 끝이 아님) — 그 시각부터 막힘 */
    @Test
    void closes30MinutesAfterSessionStart() {
        paid(NOW_AT.minusMinutes(30));

        assertThatThrownBy(() -> service.issue(3L, 30L))
                .isInstanceOf(GeneralException.class)
                .satisfies(e -> {
                    assertThat(((GeneralException) e).getErrorCode().getCode()).isEqualTo("ENTRY_CLOSED");
                    assertThat(((GeneralException) e).getData()).containsEntry("closesAt", NOW_AT);
                });
    }

    /** 마감 10초 전에 받은 QR 은 마감까지만 */
    @Test
    void qrNeverOutlivesEntryClose() {
        paid(NOW_AT.minusMinutes(30).plusSeconds(10));

        assertThat(service.issue(3L, 30L).expiresAt()).isEqualTo(NOW_AT.plusSeconds(10));
    }

    @Test
    void onlyPaidNotUsedAndNotWithdrawnBookings() {
        BookingEntity pending = booking(BookingEntity.Status.PENDING, null);
        when(bookings.findByIdAndUserId(30L, 3L)).thenReturn(Optional.of(pending));
        assertCode(() -> service.issue(3L, 30L), "ENTRY_NOT_AVAILABLE");

        BookingEntity withdrawn = booking(BookingEntity.Status.CONFIRMED, null);
        when(withdrawn.getWithdrawnAt()).thenReturn(NOW_AT.minusSeconds(5));
        when(bookings.findByIdAndUserId(30L, 3L)).thenReturn(Optional.of(withdrawn));
        assertCode(() -> service.issue(3L, 30L), "ENTRY_NOT_AVAILABLE");

        BookingEntity used = booking(BookingEntity.Status.CONFIRMED, NOW_AT.minusMinutes(10));
        when(bookings.findByIdAndUserId(30L, 3L)).thenReturn(Optional.of(used));
        assertCode(() -> service.issue(3L, 30L), "ALREADY_CHECKED_IN");

        assertCode(() -> service.issue(4L, 31L), "NOT_FOUND");   // 남의 예매 · 없는 예매
    }

    @Test
    void checkInRecordsCheckerAndReturnsSeat() {
        scanned(BookingEntity.Status.CONFIRMED, null, NOW_AT.minusMinutes(10));
        when(bookings.checkIn(30L, 77L, NOW_AT)).thenReturn(1);
        when(bookingSeats.findByBookingId(30L)).thenReturn(List.of(new BookingSeatEntity(30L, 901L)));
        ConcertSeatRepository.SeatRefRow seat = mock(ConcertSeatRepository.SeatRefRow.class);
        when(seat.getId()).thenReturn(901L);
        when(seat.getFloor()).thenReturn("1F");
        when(seat.getSection()).thenReturn("A");
        when(seat.getRowNo()).thenReturn(1);
        when(seat.getSeatNo()).thenReturn(3);
        when(seat.getPrice()).thenReturn(55000);
        when(seats.findRefs(List.of(901L))).thenReturn(List.of(seat));

        CheckInResult result = service.checkIn(42L, qr(), 77L);

        assertThat(result.bookingId()).isEqualTo(30L);
        assertThat(result.seats()).singleElement().satisfies(s -> assertThat(s.floor() + " " + s.section() + s.row() + "-" + s.number()).isEqualTo("1F A1-3"));
        assertThat(result.checkedInAt()).isEqualTo(NOW_AT);
    }

    /** ★ 같은 QR 을 두 번 — 두 번째는 409 · 언제 입장했는지 같이 */
    @Test
    void secondScanIsRejected() {
        BookingEntity used = scanned(BookingEntity.Status.CONFIRMED, NOW_AT.minusSeconds(10), NOW_AT.minusMinutes(10));
        when(bookings.checkIn(30L, 77L, NOW_AT)).thenReturn(0);
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
        scanned(BookingEntity.Status.CANCELLED, null, NOW_AT.minusMinutes(10));
        when(bookings.checkIn(30L, 77L, NOW_AT)).thenReturn(0);

        assertCode(() -> service.checkIn(42L, qr(), 77L), "ENTRY_NOT_AVAILABLE");
    }

    @Test
    void otherConcertsTicketIsRejectedWithoutRecording() {
        assertCode(() -> service.checkIn(43L, qr(), 77L), "WRONG_CONCERT");
        verify(bookings, never()).checkIn(anyLong(), anyLong(), any());
    }

    /** 11/27 검표도 입장 시간에만 — 열리기 전 · 마감 뒤에는 새 QR 이어도 기록하지 않음 */
    @Test
    void checkInOutsideEntryWindowIsRejected() {
        scanned(BookingEntity.Status.CONFIRMED, null, NOW_AT.plusMinutes(61));
        assertCode(() -> service.checkIn(42L, qr(), 77L), "ENTRY_NOT_OPEN");

        scanned(BookingEntity.Status.CONFIRMED, null, NOW_AT.minusMinutes(30));
        assertCode(() -> service.checkIn(42L, qr(), 77L), "ENTRY_CLOSED");

        verify(bookings, never()).checkIn(anyLong(), anyLong(), any());
    }

    /** ★ 캡처본 — 31초 전 QR (입장 시간 안) */
    @Test
    void screenshotIsRejectedWithoutRecording() {
        scanned(BookingEntity.Status.CONFIRMED, null, NOW_AT.minusMinutes(10));
        String old = EntryQrTokensTest.at(NOW.minusSeconds(31)).issue(30L, 42L, NOW.plusSeconds(3600)).token();

        assertCode(() -> service.checkIn(42L, old, 77L), "ENTRY_QR_EXPIRED");
        verify(bookings, never()).checkIn(anyLong(), anyLong(), any());
    }

    /** 검표 화면 — 입장이 열린 회차가 있는 공연마다 한 줄 (같은 공연의 회차 둘이 열려 있으면 먼저 시작하는 회차) */
    @Test
    void openConcertsListsOneRowPerConcert() {
        ConcertSessionEntity first = session(501L, 42L, NOW_AT.plusMinutes(20));
        ConcertSessionEntity second = session(502L, 42L, NOW_AT.plusMinutes(50));
        when(sessions.findByStartsAtLessThanEqualAndStartsAtGreaterThanOrderByStartsAtAsc(NOW_AT.plusMinutes(60), NOW_AT.minusMinutes(30)))
                .thenReturn(List.of(first, second));
        ConcertEntity c = mock(ConcertEntity.class);
        when(c.getId()).thenReturn(42L);
        when(c.getTitle()).thenReturn("혁오");
        when(c.getVenueId()).thenReturn(7L);
        when(concerts.findAllById(Set.of(42L))).thenReturn(List.of(c));
        VenueEntity v = mock(VenueEntity.class);
        when(v.getId()).thenReturn(7L);
        when(v.getName()).thenReturn("공연장");
        when(venues.findAllById(List.of(7L))).thenReturn(List.of(v));

        assertThat(service.openConcerts()).containsExactly(new ConcertRef(42L, "혁오", NOW_AT.plusMinutes(20), "공연장", 501L));
    }

    private String qr() {
        return EntryQrTokensTest.at(NOW).issue(30L, 42L, NOW.plusSeconds(3600)).token();
    }

    private void paid(OffsetDateTime sessionStartsAt) {
        BookingEntity b = booking(BookingEntity.Status.CONFIRMED, null);
        when(bookings.findByIdAndUserId(30L, 3L)).thenReturn(Optional.of(b));
        when(sessions.findById(SESSION)).thenReturn(Optional.of(new ConcertSessionEntity(42L, sessionStartsAt)));
    }

    private BookingEntity scanned(BookingEntity.Status status, OffsetDateTime checkedInAt, OffsetDateTime sessionStartsAt) {
        BookingEntity b = booking(status, checkedInAt);
        when(bookings.findById(30L)).thenReturn(Optional.of(b));
        when(sessions.findById(SESSION)).thenReturn(Optional.of(new ConcertSessionEntity(42L, sessionStartsAt)));
        return b;
    }

    private static BookingEntity booking(BookingEntity.Status status, OffsetDateTime checkedInAt) {
        BookingEntity b = mock(BookingEntity.class);
        when(b.getId()).thenReturn(30L);
        when(b.getConcertId()).thenReturn(42L);
        when(b.getSessionId()).thenReturn(SESSION);
        when(b.getStatus()).thenReturn(status);
        when(b.getCheckedInAt()).thenReturn(checkedInAt);
        return b;
    }

    private static ConcertSessionEntity session(long id, long concertId, OffsetDateTime startsAt) {
        ConcertSessionEntity s = mock(ConcertSessionEntity.class);
        when(s.getId()).thenReturn(id);
        when(s.getConcertId()).thenReturn(concertId);
        when(s.getStartsAt()).thenReturn(startsAt);
        return s;
    }

    private EntryService at(Instant now) {
        return new EntryService(bookings, bookingSeats, seats, concerts, sessions, venues,
                EntryQrTokensTest.at(now), new EntryWindow(PROPS), Clock.fixed(now, ZoneOffset.UTC));
    }
}
