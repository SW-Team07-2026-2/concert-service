package com.indie.concert.domain.seat.repository;

import java.time.Duration;

/**
 * 분산 락 포트 — 구현은 RedisSeatLock (캐시용 Redis · SET NX PX + 내 값일 때만 삭제).
 * 락은 DB 경합을 줄이는 1차 방어입니다 (FR-CON-27). 🔴 Redis 가 죽으면 잠그지 않고 진행하는 핸들을 돌려줍니다 —
 * 좌석 중복은 조건부 UPDATE 가, 1인 1예매는 uq_booking_active 가 최종으로 막습니다.
 */
public interface SeatLock {

    /** null = 다른 요청이 잡고 있음 */
    Handle tryLock(String key, Duration ttl);

    interface Handle extends AutoCloseable {
        @Override
        void close();
    }
}
