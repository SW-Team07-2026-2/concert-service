package com.indie.concert.domain.seat.exception;

import com.indie.concert.global.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

/** 좌석 에러 코드 — code 는 enum 이름 그대로 응답에 나갑니다 */
public enum SeatErrorCode implements BaseErrorCode {

    SEAT_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "이 공연은 1인 1석만 예매할 수 있습니다"),
    SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "좌석을 찾을 수 없습니다"),
    SEAT_UNAVAILABLE(HttpStatus.CONFLICT, "이미 선택된 좌석입니다"),
    SEAT_LOCKED(HttpStatus.CONFLICT, "다른 요청을 처리하고 있습니다. 잠시 후 다시 시도해주세요");

    private final HttpStatus status;
    private final String message;

    SeatErrorCode(HttpStatus status, String message) {
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
