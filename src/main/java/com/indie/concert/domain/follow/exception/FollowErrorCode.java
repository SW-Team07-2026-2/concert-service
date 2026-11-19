package com.indie.concert.domain.follow.exception;

import com.indie.concert.global.common.exception.BaseErrorCode;
import org.springframework.http.HttpStatus;

/** 팔로우 탭 에러 코드 — code 는 enum 이름 그대로 응답에 나갑니다 */
public enum FollowErrorCode implements BaseErrorCode {

    FOLLOW_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "팔로우 목록을 불러오지 못했습니다. 잠시 후 다시 시도해주세요");

    private final HttpStatus status;
    private final String message;

    FollowErrorCode(HttpStatus status, String message) {
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
