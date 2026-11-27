package com.indie.concert.domain.concert.exception;

import com.indie.concert.global.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

/** 회차 에러 코드 (11/27) — code 는 enum 이름 그대로 응답에 나갑니다 */
public enum SessionErrorCode implements BaseErrorCode {

    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "회차를 찾을 수 없습니다"),                     // 다른 공연의 회차도 이것
    SESSION_REQUIRED(HttpStatus.BAD_REQUEST, "회차를 골라주세요"),                         // 회차가 둘 이상인 공연의 좌석 화면에 sessionId 없음
    SESSION_CLOSED(HttpStatus.CONFLICT, "이미 시작한 회차입니다"),                          // 시작한 회차의 좌석 선점
    SESSION_HAS_BOOKINGS(HttpStatus.CONFLICT, "예매 기록이 있는 회차는 지울 수 없습니다");     // 관리자 회차 삭제 — 일정은 바꿀 수 있음

    private final HttpStatus status;
    private final String message;

    SessionErrorCode(HttpStatus status, String message) {
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
