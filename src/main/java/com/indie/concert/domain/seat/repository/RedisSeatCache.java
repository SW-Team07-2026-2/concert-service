package com.indie.concert.domain.seat.repository;

import com.indie.concert.domain.seat.dto.response.SeatMap;
import com.indie.concert.global.config.ConcertProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 캐시용 Redis(allkeys-lru)는 죽을 수 있습니다 — 모든 실패는 로그만 남기고 넘어갑니다 (fail-open).
 * 값 JSON 은 seatCacheRedis 템플릿의 직렬화기가 맡습니다 (62B) — 깨진 값도 읽기 실패로 보고 DB 로 갑니다.
 */
@Component
public class RedisSeatCache implements SeatCache {

    private static final Logger log = LoggerFactory.getLogger(RedisSeatCache.class);
    private static final String PREFIX = "concert:cache:seats:";

    private final RedisTemplate<String, SeatMap> seatCacheRedis;
    private final Duration ttl;

    public RedisSeatCache(@Qualifier("seatCacheRedis") RedisTemplate<String, SeatMap> seatCacheRedis, ConcertProperties props) {
        this.seatCacheRedis = seatCacheRedis;
        this.ttl = Duration.ofMillis(props.cache().seatsTtlMs());
    }

    @Override
    public Optional<SeatMap> get(long concertId) {
        try {
            return Optional.ofNullable(seatCacheRedis.opsForValue().get(PREFIX + concertId));
        } catch (Exception e) {
            log.warn("seat_cache_get_failed concertId={} error={}", concertId, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void put(SeatMap map) {
        try {
            seatCacheRedis.opsForValue().set(PREFIX + map.concertId(), map, ttl);
        } catch (Exception e) {
            log.warn("seat_cache_put_failed concertId={} error={}", map.concertId(), e.toString());
        }
    }
}
