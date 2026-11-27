package com.indie.concert.domain.booking.service;

import com.indie.concert.domain.booking.dto.response.EntryInfo;
import com.indie.concert.domain.booking.entity.BookingEntity;
import com.indie.concert.global.config.ConcertProperties;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 입장 시간 (11/27) — 회차 시작 1시간 전부터 시작 30분 뒤까지 (entry.open-before-minutes · close-after-minutes).
 * QR 발급 · 검표 · 티켓 조회의 entryStatus 가 모두 이 계산 하나를 씁니다. 경계: opensAt ≤ 지금 < closesAt.
 * 마감(closesAt)은 입장 마감이지 공연 종료가 아닙니다.
 */
@Component
public class EntryWindow {

    /**
     * 티켓 화면의 입장 상태 — 항상 값이 있습니다 (null 없음).
     * <pre>
     * 예매 \ 시간                        전 (opensAt 이전)  중 (opensAt ~ closesAt)  후 (closesAt 이후)
     * CONFIRMED · 입장 전               NOT_OPEN          AVAILABLE               CLOSED
     * CONFIRMED · 입장함                (생기지 않음)        CHECKED_IN              CHECKED_IN
     * PENDING · CANCELLED · EXPIRED     UNAVAILABLE       UNAVAILABLE             UNAVAILABLE
     * </pre>
     */
    public enum Status { NOT_OPEN, AVAILABLE, CHECKED_IN, CLOSED, UNAVAILABLE }

    private final long openBeforeMinutes;
    private final long closeAfterMinutes;

    public EntryWindow(ConcertProperties props) {
        this.openBeforeMinutes = props.entry().openBeforeMinutes();
        this.closeAfterMinutes = props.entry().closeAfterMinutes();
    }

    public OffsetDateTime opensAt(OffsetDateTime sessionStartsAt) {
        return sessionStartsAt.minusMinutes(openBeforeMinutes);
    }

    public OffsetDateTime closesAt(OffsetDateTime sessionStartsAt) {
        return sessionStartsAt.plusMinutes(closeAfterMinutes);
    }

    public boolean notOpenYet(OffsetDateTime sessionStartsAt, OffsetDateTime now) {
        return now.isBefore(opensAt(sessionStartsAt));
    }

    public boolean closed(OffsetDateTime sessionStartsAt, OffsetDateTime now) {
        return !now.isBefore(closesAt(sessionStartsAt));
    }

    public Status status(BookingEntity booking, OffsetDateTime sessionStartsAt, OffsetDateTime now) {
        if (booking.getStatus() != BookingEntity.Status.CONFIRMED || booking.getWithdrawnAt() != null) return Status.UNAVAILABLE;
        if (booking.getCheckedInAt() != null) return Status.CHECKED_IN;
        if (notOpenYet(sessionStartsAt, now)) return Status.NOT_OPEN;
        if (closed(sessionStartsAt, now)) return Status.CLOSED;
        return Status.AVAILABLE;
    }

    /** 티켓 목록 · 상세에 넣는 입장 필드 5개 */
    public EntryInfo info(BookingEntity booking, OffsetDateTime sessionStartsAt, OffsetDateTime now) {
        return new EntryInfo(status(booking, sessionStartsAt, now).name(), opensAt(sessionStartsAt), closesAt(sessionStartsAt),
                booking.getCheckedInAt(), now);
    }

    public long openBeforeMinutes() { return openBeforeMinutes; }

    public long closeAfterMinutes() { return closeAfterMinutes; }
}
