package com.indie.concert.domain.booking.service;

import com.indie.concert.domain.booking.dto.response.EntryInfo;
import com.indie.concert.domain.booking.entity.BookingEntity;
import com.indie.concert.global.config.ConcertProperties;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 11/27 입장 상태 — 회차 시작 −60분 ≤ 지금 < 시작 +30분 · entryStatus 는 항상 값이 있음 */
class EntryWindowTest {

    private static final OffsetDateTime START = OffsetDateTime.parse("2026-12-20T19:00:00+09:00");
    private final EntryWindow window = new EntryWindow(new ConcertProperties(null, null, null, null, null, null, null, null, null, null,
            new ConcertProperties.Entry(30, 60, 30)));

    @Test
    void boundariesAreOpensInclusiveClosesExclusive() {
        BookingEntity paid = booking(BookingEntity.Status.CONFIRMED, null, null);

        assertThat(window.status(paid, START, START.minusMinutes(60).minusNanos(1))).isEqualTo(EntryWindow.Status.NOT_OPEN);
        assertThat(window.status(paid, START, START.minusMinutes(60))).isEqualTo(EntryWindow.Status.AVAILABLE);
        assertThat(window.status(paid, START, START.plusMinutes(30).minusNanos(1))).isEqualTo(EntryWindow.Status.AVAILABLE);
        assertThat(window.status(paid, START, START.plusMinutes(30))).isEqualTo(EntryWindow.Status.CLOSED);
    }

    /** 입장한 표는 마감 뒤에도 CHECKED_IN · 결제 전 · 취소 · 만료 · 탈퇴 정리 중은 언제나 UNAVAILABLE */
    @Test
    void checkedInStaysAndInactiveIsUnavailable() {
        BookingEntity entered = booking(BookingEntity.Status.CONFIRMED, START.minusMinutes(10), null);
        assertThat(window.status(entered, START, START.minusMinutes(5))).isEqualTo(EntryWindow.Status.CHECKED_IN);
        assertThat(window.status(entered, START, START.plusDays(1))).isEqualTo(EntryWindow.Status.CHECKED_IN);

        for (BookingEntity.Status s : new BookingEntity.Status[]{BookingEntity.Status.PENDING, BookingEntity.Status.CANCELLED,
                BookingEntity.Status.EXPIRED}) {
            assertThat(window.status(booking(s, null, null), START, START)).isEqualTo(EntryWindow.Status.UNAVAILABLE);
        }
        BookingEntity withdrawn = booking(BookingEntity.Status.CONFIRMED, null, START.minusDays(3));
        assertThat(window.status(withdrawn, START, START)).isEqualTo(EntryWindow.Status.UNAVAILABLE);
    }

    @Test
    void infoCarriesWindowAndServerTime() {
        OffsetDateTime now = START.minusHours(2);
        EntryInfo info = window.info(booking(BookingEntity.Status.CONFIRMED, null, null), START, now);

        assertThat(info.entryStatus()).isEqualTo("NOT_OPEN");
        assertThat(info.entryQrOpensAt()).isEqualTo(START.minusMinutes(60));
        assertThat(info.entryQrClosesAt()).isEqualTo(START.plusMinutes(30));
        assertThat(info.checkedInAt()).isNull();
        assertThat(info.serverTime()).isEqualTo(now);
    }

    private static BookingEntity booking(BookingEntity.Status status, OffsetDateTime checkedInAt, OffsetDateTime withdrawnAt) {
        BookingEntity b = mock(BookingEntity.class);
        when(b.getStatus()).thenReturn(status);
        when(b.getCheckedInAt()).thenReturn(checkedInAt);
        when(b.getWithdrawnAt()).thenReturn(withdrawnAt);
        return b;
    }
}
