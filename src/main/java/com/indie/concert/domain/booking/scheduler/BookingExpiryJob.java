package com.indie.concert.domain.booking.scheduler;

import com.indie.concert.domain.booking.service.BookingService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 선택 만료 정리 트리거 — 30초마다 (FR-CON-15).
 * <pre>
 * ① 결제 승인 응답을 못 받은 채 1분이 지난 PAYING → Payment 에 결과를 물음 → 승인이면 확정(SOLD) · 아니면 EXPIRED · 좌석 반환
 * ② 선점 서버 마감이 지난 예매            → 결제 무효화 → EXPIRED · 좌석 반환 (다음 사람이 이미 가져갔으면 그 좌석은 그대로)
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
