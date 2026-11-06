package com.indie.concert.domain.booking.exception;

import com.indie.concert.global.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

/** 예매 에러 코드 — code 는 enum 이름 그대로 응답에 나갑니다 */
public enum BookingErrorCode implements BaseErrorCode {

    ALREADY_PAID(HttpStatus.CONFLICT, "이미 결제된 예매입니다 — 예매 취소를 이용해주세요"),
    // 11/04 77A 좌석 선점
    HOLD_EXISTS(HttpStatus.CONFLICT, "이미 선택한 좌석이 있습니다. 이전 단계에서 선택을 취소한 뒤 다시 골라주세요"),   // result.bookingId = 내 선점
    HOLD_EXPIRED(HttpStatus.GONE, "좌석 선점 시간이 지났습니다. 좌석을 다시 선택해주세요"),                         // SELECTION_EXPIRED 대체 · 토스 승인을 부르지 않음
    PAYMENT_PENDING(HttpStatus.SERVICE_UNAVAILABLE, "결제 결과를 확인하고 있습니다. 잠시 후 예매 내역을 확인해주세요"),   // 승인 응답 없음 — 좌석은 결제 중 그대로
    BOOKING_CANCELLED(HttpStatus.CONFLICT, "취소된 예매입니다. 좌석을 다시 선택해주세요"),   // 결제 준비 실패 · 사용자 취소
    BOOKING_NOT_CANCELLABLE(HttpStatus.CONFLICT, "취소할 수 없는 예매입니다"),
    PAYMENT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "결제를 처리하지 못했습니다. 잠시 후 다시 시도해주세요"),

    // 입장 QR · 검표 (11/01)
    ENTRY_NOT_OPEN(HttpStatus.CONFLICT, "아직 입장 QR 이 열리지 않았습니다"),
    ENTRY_CLOSED(HttpStatus.CONFLICT, "입장 시간이 지났습니다"),
    ENTRY_NOT_AVAILABLE(HttpStatus.CONFLICT, "결제가 끝난 예매만 입장할 수 있습니다"),
    ALREADY_CHECKED_IN(HttpStatus.CONFLICT, "이미 입장한 티켓입니다"),
    WRONG_CONCERT(HttpStatus.CONFLICT, "다른 공연의 티켓입니다"),
    ENTRY_QR_EXPIRED(HttpStatus.BAD_REQUEST, "만료된 QR 입니다. 앱 화면의 새 QR 을 보여주세요"),
    ENTRY_QR_INVALID(HttpStatus.BAD_REQUEST, "입장 QR 이 아닙니다");

    private final HttpStatus status;
    private final String message;

    BookingErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public HttpStatus getStatus() { return status; }

    @Override
    public String getCode() { return name(); }

    @Override
    public String getMessage() { return message; }
}
