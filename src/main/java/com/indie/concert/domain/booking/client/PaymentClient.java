package com.indie.concert.domain.booking.client;

import com.indie.concert.global.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;

/**
 * Payment 호출 포트 — 구현은 HttpPaymentClient (내부 토큰 · 타임아웃 3초 · orderType=TICKET).
 * 불통 · 5xx · 타임아웃은 전부 Unavailable — 호출자가 보상합니다. 승인(confirm)의 4xx 는 Rejected (돈이 나가기 전 거부).
 */
public interface PaymentClient {

    /** 결제 준비 — 🔴 금액은 서버가 seat.price 로 정한 값입니다. Payment 는 confirm 금액을 이 값과 대조합니다 */
    Prepared prepare(long bookingId, long userId, int amount);

    /**
     * 결제 취소 — refundApproved=false 면 승인 전(PENDING)만 무효화합니다 (만료 처리용: 방금 결제된 건을 환불하지 않게).
     * 결제 행이 없으면 NOT_FOUND (준비가 실패했던 예매). 그 외는 결제의 최종 상태 (CANCELLED · DECLINED · APPROVED).
     */
    String cancel(long bookingId, String reason, boolean refundApproved);

    /**
     * 결제 승인 — ★ 선점 좌석을 결제 중(PAYING)으로 바꾼 뒤에만 부릅니다 (11/04 77A). Payment 가 prepare 금액과 대조합니다.
     * paymentKey 는 토스 successUrl 의 값 · forceDecline 은 사용자의 X-Force-Decline 그대로 (토스 테스트 코드). APPROVED · DECLINED 는 결과, 승인 전 거부는 Rejected.
     */
    Confirmed confirm(long bookingId, long userId, String paymentKey, int amount, String forceDecline);

    /** pgOrderId — 토스 결제창(SDK)의 orderId. paymentKey 는 결제창 인증 뒤 토스가 프론트에 줍니다 */
    record Prepared(String pgOrderId, String status, OffsetDateTime expiresAt) {}

    record Confirmed(String status, String reason) {
        public boolean approved() { return "APPROVED".equals(status); }
    }

    /** Payment 가 4xx 로 승인을 거부 — 돈이 나가지 않았습니다. 코드 · 메시지는 그대로 전달 (AMOUNT_MISMATCH · PAYMENT_EXPIRED …) */
    class Rejected extends RuntimeException {
        private final BaseErrorCode errorCode;

        public Rejected(HttpStatus status, String code, String message) {
            super(message);
            this.errorCode = new BaseErrorCode() {
                @Override public HttpStatus getStatus() { return status; }
                @Override public String getCode() { return code; }
                @Override public String getMessage() { return message; }
            };
        }

        public BaseErrorCode errorCode() { return errorCode; }
    }

    class Unavailable extends RuntimeException {
        public Unavailable(String op, Throwable cause) {
            super("payment " + op + " failed: " + cause.getMessage(), cause);
        }
    }
}
