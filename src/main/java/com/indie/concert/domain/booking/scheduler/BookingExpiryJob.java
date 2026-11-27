package com.indie.concert.domain.booking.scheduler;

import com.indie.concert.domain.booking.service.BookingService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 승인 결과 미확인 예매, 선점 만료와 미완료 탈퇴 정리를 주기적으로 재처리합니다.
 * 결제 취소의 멱등성과 조건부 상태 변경으로 여러 replica의 중복 실행을 처리합니다.
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
        bookingService.retryWithdrawn(BATCH);
    }
}
