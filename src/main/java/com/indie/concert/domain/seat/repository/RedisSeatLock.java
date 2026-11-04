package com.indie.concert.domain.seat.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** SET key uuid NX PX ttl → 풀 때는 값이 내 uuid 일 때만 DEL (Lua) — TTL 이 지나 남이 잡은 락을 지우지 않게 (08 §8.3) */
@Component
public class RedisSeatLock implements SeatLock {

    private static final Logger log = LoggerFactory.getLogger(RedisSeatLock.class);
    private static final RedisScript<Long> UNLOCK = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);
    private static final Handle UNLOCKED = () -> { };

    private final StringRedisTemplate redis;

    public RedisSeatLock(@Qualifier("cacheRedis") StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Handle tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        try {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, ttl))) return null;
        } catch (DataAccessException e) {
            log.warn("seat_lock_skipped key={} error={}", key, e.toString());
            return UNLOCKED;
        }
        return () -> {
            try {
                redis.execute(UNLOCK, List.of(key), token);
            } catch (DataAccessException e) {
                log.warn("seat_unlock_failed key={} error={}", key, e.toString());   // TTL(3초)이 상한
            }
        };
    }
}
