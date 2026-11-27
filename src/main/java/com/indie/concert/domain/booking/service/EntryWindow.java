package com.indie.concert.domain.booking.service;

import com.indie.concert.domain.booking.dto.response.EntryInfo;
import com.indie.concert.domain.booking.entity.BookingEntity;
import com.indie.concert.global.config.ConcertProperties;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 입장 시간 - 회차 시작 1시간 전부터 시작 30분 뒤까지 (entry.open-before-minutes, close-after-minutes).
 * QR 발급, 검표, 티켓 조회의 entryStatus 가 모두 이 계산 하나를 씁니다. 경계: opensAt <= 지금 < closesAt.
 * 마감(closesAt)은 입장 마감이지 공연 종료가 아닙니다.
 */
@Component
public class EntryWindow {

    /** 예매 상태, 입장 기록과 입장 시간으로 티켓 화면 상태를 구분합니다. 미확정 예매는 UNAVAILABLE입니다. */
    public enum Status { NOT_OPEN, AVAILABLE, CHECKED_IN, CLOSED, UNAVAILABLE }

    private final long openBeforeMinutes;
    private final long closeAfterMinutes;

    public EntryWindow(ConcertProperties props) {
        this.openBeforeMinutes = props.entry().openBeforeMinutes();
        this.closeAfterMinutes = props.entry().closeAfterMinutes();
    }

    /**
     * 회차의 입장 시작 시각을 구합니다.
     *
     * @param sessionStartsAt 예매한 회차의 시작 시각
     */
    public OffsetDateTime opensAt(OffsetDateTime sessionStartsAt) {
        return sessionStartsAt.minusMinutes(openBeforeMinutes);
    }

    /**
     * 회차의 입장 마감 시각을 구합니다.
     *
     * @param sessionStartsAt 예매한 회차의 시작 시각
     */
    public OffsetDateTime closesAt(OffsetDateTime sessionStartsAt) {
        return sessionStartsAt.plusMinutes(closeAfterMinutes);
    }

    /**
     * 입장 시작 전인지 확인합니다.
     *
     * @param sessionStartsAt 예매한 회차의 시작 시각
     * @param now 판정 기준 시각
     */
    public boolean notOpenYet(OffsetDateTime sessionStartsAt, OffsetDateTime now) {
        return now.isBefore(opensAt(sessionStartsAt));
    }

    /**
     * 입장 마감이 지났는지 확인합니다.
     *
     * @param sessionStartsAt 예매한 회차의 시작 시각
     * @param now 판정 기준 시각
     */
    public boolean closed(OffsetDateTime sessionStartsAt, OffsetDateTime now) {
        return !now.isBefore(closesAt(sessionStartsAt));
    }

    /**
     * 예매와 현재 시각으로 입장 상태를 구합니다.
     *
     * @param booking 입장 상태를 확인할 예매
     * @param sessionStartsAt 예매한 회차의 시작 시각
     * @param now 판정 기준 시각
     */
    public Status status(BookingEntity booking, OffsetDateTime sessionStartsAt, OffsetDateTime now) {
        if (booking.getStatus() != BookingEntity.Status.CONFIRMED || booking.getWithdrawnAt() != null) return Status.UNAVAILABLE;
        if (booking.getCheckedInAt() != null) return Status.CHECKED_IN;
        if (notOpenYet(sessionStartsAt, now)) return Status.NOT_OPEN;
        if (closed(sessionStartsAt, now)) return Status.CLOSED;
        return Status.AVAILABLE;
    }

    /**
     * 입장 가능 시간과 상태를 반환합니다.
     *
     * @param booking 입장 상태를 확인할 예매
     * @param sessionStartsAt 예매한 회차의 시작 시각
     * @param now 판정 기준 시각
     */
    public EntryInfo info(BookingEntity booking, OffsetDateTime sessionStartsAt, OffsetDateTime now) {
        return new EntryInfo(status(booking, sessionStartsAt, now).name(), opensAt(sessionStartsAt), closesAt(sessionStartsAt),
                booking.getCheckedInAt(), now);
    }

    /** 회차 시작 전 입장 허용 시간을 분 단위로 반환합니다. */
    public long openBeforeMinutes() { return openBeforeMinutes; }

    /** 회차 시작 후 입장 허용 시간을 분 단위로 반환합니다. */
    public long closeAfterMinutes() { return closeAfterMinutes; }
}
