package com.indie.concert.domain.seat.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.concert.domain.seat.dto.response.SeatMap;
import com.indie.concert.global.config.ConcertProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** 캐시용 Redis(allkeys-lru)는 죽을 수 있습니다 — 모든 실패는 로그만 남기고 넘어갑니다 (fail-open) */
@Component
public class RedisSeatCache implements SeatCache {

    private static final Logger log = LoggerFactory.getLogger(RedisSeatCache.class);
    private static final String PREFIX = "concert:cache:seats:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisSeatCache(@Qualifier("cacheRedis") StringRedisTemplate redis, ObjectMapper objectMapper, ConcertProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofMillis(props.cache().seatsTtlMs());
    }

    @Override
    public Optional<SeatMap> get(long concertId) {
        try {
            String json = redis.opsForValue().get(PREFIX + concertId);
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, SeatMap.class));
        } catch (Exception e) {
            log.warn("seat_cache_get_failed concertId={} error={}", concertId, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void put(SeatMap map) {
        try {
            redis.opsForValue().set(PREFIX + map.concertId(), objectMapper.writeValueAsString(map), ttl);
        } catch (Exception e) {
            log.warn("seat_cache_put_failed concertId={} error={}", map.concertId(), e.toString());
        }
    }
}
