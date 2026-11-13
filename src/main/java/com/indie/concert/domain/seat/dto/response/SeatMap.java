package com.indie.concert.domain.seat.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 좌석 화면 — 층 → 구역(모양 · 기울기) → 좌석(좌표 · 기울기 · 가격 · 상태). 좌표는 무대 그리기 캔버스 기준 0~1.
 * available = 판매되지 않은 좌석 수 (선택 중은 풀릴 수 있어 포함) · maxSeatsPerUser = 한 번에 고를 수 있는 좌석 수.
 * expiresAt · expiresIn — 입장 토큰(X-Entry-Token)을 보냈을 때만: 화면 타이머 마감과 이 API 를 부른 시점 기준 남은 초.
 * 프론트는 expiresIn 으로 타이머를 돌립니다 (기기 시계가 틀려도 맞음). 배치 · 상태는 2초 캐시, 타이머는 요청마다 계산합니다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SeatMap(long concertId, int maxSeatsPerUser, long total, long available, OffsetDateTime expiresAt,
                      Long expiresIn, List<FloorView> floors) {

    public SeatMap withTimer(OffsetDateTime expiresAt, long expiresIn) {
        return new SeatMap(concertId, maxSeatsPerUser, total, available, expiresAt, expiresIn, floors);
    }
}
