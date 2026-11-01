package com.indie.concert.domain.booking.exception;

import com.indie.concert.global.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

/** 예매 에러 코드 — code 는 enum 이름 그대로 응답에 나갑니다 */
public enum BookingErrorCode implements BaseErrorCode {

    ALREADY_PAID(HttpStatus.CONFLICT, "이미 결제된 예매입니다 — 예매 취소를 이용해주세요"),
    SELECTION_EXPIRED(HttpStatus.CONFLICT, "좌석 선택이 만료되었습니다. 좌석을 다시 선택해주세요"),
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
