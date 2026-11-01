package com.indie.concert.global.common.exception;

import java.util.LinkedHashMap;
import java.util.Map;

/** 에러 코드로 던지는 예외 — GlobalExceptionHandler 가 ApiResponse 실패 응답으로 바꿉니다 */
public class GeneralException extends RuntimeException {

    private final BaseErrorCode errorCode;
    private final Map<String, Object> data = new LinkedHashMap<>();

    public GeneralException(BaseErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    /** 같은 코드라도 상황에 맞는 메시지가 필요할 때 */
    public GeneralException(BaseErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** 실패 응답의 result 에 값을 싣습니다 — 409 ALREADY_IN_QUEUE 의 rank 같은 것 (15 §4) */
    public GeneralException with(String key, Object value) {
        data.put(key, value);
        return this;
    }

    public Map<String, Object> getData() { return data; }

    public BaseErrorCode getErrorCode() { return errorCode; }
}
