package com.indie.concert.domain.seat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.concert.domain.seat.dto.response.SeatMap;
import com.indie.concert.global.common.response.ApiResponse;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

/**
 * ✏️ 11/25 좌석 화면 응답 본문 — 타이머 없는 ApiResponse JSON 바이트 + 타이머를 끼울 자리("result":{ 바로 뒤).
 * ApiResponse 는 isSuccess · code · message · result 순서라 처음 나오는 "result":{ 가 응답의 result 입니다.
 * 타이머를 앞에 끼우면 키 순서만 다르고 내용은 Jackson 이 SeatMap.withTimer 를 직렬화한 것과 같습니다 (SeatServiceTest)
 */
record SeatMapBody(byte[] json, int timerAt) {

    private static final byte[] RESULT = "\"result\":{".getBytes(StandardCharsets.UTF_8);

    static SeatMapBody of(ObjectMapper objectMapper, SeatMap map) {
        byte[] json = write(() -> objectMapper.writeValueAsBytes(ApiResponse.onSuccess(map)));
        int at = indexOf(json, RESULT);
        if (at < 0) throw new IllegalStateException("좌석 화면 응답에 result 가 없습니다");
        return new SeatMapBody(json, at + RESULT.length);
    }

    /** 사람마다 다른 타이머 — 캐시한 바이트는 바꾸지 않고 새 배열에 복사합니다 */
    byte[] withTimer(ObjectMapper objectMapper, OffsetDateTime expiresAt, long expiresIn) {
        String at = write(() -> objectMapper.writeValueAsString(expiresAt));
        byte[] timer = ("\"expiresAt\":" + at + ",\"expiresIn\":" + expiresIn + ",").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[json.length + timer.length];
        System.arraycopy(json, 0, out, 0, timerAt);
        System.arraycopy(timer, 0, out, timerAt, timer.length);
        System.arraycopy(json, timerAt, out, timerAt + timer.length, json.length - timerAt);
        return out;
    }

    private static int indexOf(byte[] data, byte[] target) {
        outer:
        for (int i = 0; i <= data.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private interface Write<T> {
        T get() throws JsonProcessingException;
    }

    private static <T> T write(Write<T> write) {
        try {
            return write.get();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("좌석 화면 응답을 JSON 으로 만들지 못했습니다", e);
        }
    }
}
