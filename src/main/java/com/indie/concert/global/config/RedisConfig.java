package com.indie.concert.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.concert.domain.seat.dto.response.SeatMap;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.Duration;

/**
 * Redis 두 개 — 정책이 인스턴스 단위라 섞을 수 없습니다 (02 ⑤ · 16 §4).
 * <pre>
 * queueRedis  대기열 · 입장 토큰       noeviction — 밀려나면 순번 · 입장권이 사라집니다
 * cacheRedis  분산 락 · 좌석 캐시      allkeys-lru — 밀려나도 DB 조건부 UPDATE 가 최종 보장
 *   seatCacheRedis  concert:cache:seats:{id} → SeatMap JSON (같은 cacheRedis 연결 · 62B 타입 템플릿)
 * </pre>
 * 타임아웃 300ms · 끊겨 있으면 명령을 쌓지 않고 바로 실패 — 대기열은 DB 폴백(degraded), 락 · 캐시는 건너뜁니다.
 */
@Configuration
public class RedisConfig {

    @Bean
    @Primary
    public LettuceConnectionFactory queueRedisConnectionFactory(ConcertProperties props) {
        return factory(props.redis().queue());
    }

    @Bean
    public LettuceConnectionFactory cacheRedisConnectionFactory(ConcertProperties props) {
        return factory(props.redis().cache());
    }

    @Bean
    @Primary
    public StringRedisTemplate queueRedis(@Qualifier("queueRedisConnectionFactory") RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    public StringRedisTemplate cacheRedis(@Qualifier("cacheRedisConnectionFactory") RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    public RedisTemplate<String, SeatMap> seatCacheRedis(
            @Qualifier("cacheRedisConnectionFactory") RedisConnectionFactory factory, ObjectMapper objectMapper) {
        RedisTemplate<String, SeatMap> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(new Jackson2JsonRedisSerializer<>(objectMapper, SeatMap.class));
        return template;
    }

    private static LettuceConnectionFactory factory(ConcertProperties.Node node) {
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(300))
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(300)).build())
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .build())
                .build();
        return new LettuceConnectionFactory(new RedisStandaloneConfiguration(node.host(), node.port()), client);
    }
}
