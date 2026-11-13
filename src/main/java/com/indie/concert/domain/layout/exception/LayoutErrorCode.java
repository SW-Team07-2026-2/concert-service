package com.indie.concert.domain.layout.exception;

import com.indie.concert.global.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

/** 공연장 배치 에러 코드 — code 는 enum 이름 그대로 응답에 나갑니다 */
public enum LayoutErrorCode implements BaseErrorCode {

    SECTION_IN_USE(HttpStatus.CONFLICT, "이 구역 좌석을 판매하는 공연이 있어 배치를 바꿀 수 없습니다");

    private final HttpStatus status;
    private final String message;

    LayoutErrorCode(HttpStatus status, String message) {
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
