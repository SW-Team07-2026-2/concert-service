package com.indie.concert.domain.queue.exception;

import com.indie.concert.global.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

/** 대기열 에러 코드 — code 는 enum 이름 그대로 응답에 나갑니다 */
public enum QueueErrorCode implements BaseErrorCode {

    QUEUE_NOT_OPEN(HttpStatus.CONFLICT, "아직 예매가 열리지 않았습니다"),
    ALREADY_IN_QUEUE(HttpStatus.CONFLICT, "이미 대기 중입니다"),
    QUEUE_DISABLED(HttpStatus.CONFLICT, "대기열 없이 예매하는 공연입니다"),
    QUEUE_CLOSED(HttpStatus.GONE, "판매가 끝난 공연입니다"),
    ENTRY_TOKEN_REQUIRED(HttpStatus.FORBIDDEN, "대기열 입장 후 이용할 수 있습니다"),
    QUEUE_NOT_ADMITTED(HttpStatus.FORBIDDEN, "입장 시간(7분)이 지났거나 아직 입장 전입니다. 대기열에 다시 들어가주세요"),
    ENTRY_TOKEN_INVALID(HttpStatus.FORBIDDEN, "입장 토큰이 올바르지 않습니다. 새로고침 후 다시 시도해주세요"),
    NOT_IN_QUEUE(HttpStatus.NOT_FOUND, "대기열에 없습니다"),
    QUEUE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "대기열을 잠시 사용할 수 없습니다. 잠시 후 다시 시도해주세요");

    private final HttpStatus status;
    private final String message;

    QueueErrorCode(HttpStatus status, String message) {
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
