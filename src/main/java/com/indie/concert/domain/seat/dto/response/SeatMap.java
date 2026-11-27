package com.indie.concert.domain.seat.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 회차별 구역과 좌석 좌표, 가격, 상태 응답. 좌표 범위는 0부터 1입니다.
 * 입장 토큰이 있으면 남은 시간을 반환하고 배치 캐시와 별도로 타이머를 계산합니다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SeatMap(long concertId, long sessionId, int maxSeatsPerUser, long total, long available, OffsetDateTime expiresAt,
                      Long expiresIn, List<FloorView> floors) {

    public SeatMap withTimer(OffsetDateTime expiresAt, long expiresIn) {
        return new SeatMap(concertId, sessionId, maxSeatsPerUser, total, available, expiresAt, expiresIn, floors);
    }
}
