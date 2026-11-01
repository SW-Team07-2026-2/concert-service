package com.indie.concert.domain.seat.repository;

import com.indie.concert.domain.seat.dto.response.SeatMap;

import java.util.Optional;

/**
 * 좌석 배치 캐시 포트 — concert:cache:seats:{id} · TTL 2초 (08 §8.3). 구현은 RedisSeatCache.
 * 🔴 구현은 모든 실패를 삼켜야 합니다 — 캐시가 죽어도 DB 로 응답합니다. 지우지 않고 2초 만료에만 맡깁니다 (점유마다 지우면 캐시가 의미 없음).
 */
public interface SeatCache {

    Optional<SeatMap> get(long concertId);

    void put(SeatMap map);
}
