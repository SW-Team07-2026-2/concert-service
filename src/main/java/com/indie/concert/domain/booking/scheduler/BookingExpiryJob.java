package com.indie.concert.domain.booking.scheduler;

import com.indie.concert.domain.booking.service.BookingService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 선택 만료 정리 트리거 — 30초마다 (FR-CON-15).
 * <pre>
 * ① 고르고 5분 + 60초 안에 결제하지 않은 예매   → 결제 무효화 → EXPIRED          (좌석은 잡은 적이 없어 그대로)
 * ② 승인 응답을 못 받은 채 claim 시간이 지난 예매 → 결제 무효화 → EXPIRED · 좌석 반환 (그 사이 승인됐으면 건드리지 않음)
 * </pre>
 * 모든 replica 에서 돕니다 — 결제 취소는 멱등, 상태 전이는 조건부 UPDATE 라 같은 건을 동시에 집어도 한 번만 반영됩니다.
 */
@Component
public class BookingExpiryJob {

    private static final int BATCH = 50;

    private final BookingService bookingService;

    public BookingExpiryJob(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Scheduled(fixedDelayString = "${app.booking.expiry-interval-ms}")
    public void expire() {
        bookingService.expirePending(BATCH);
    }
}
