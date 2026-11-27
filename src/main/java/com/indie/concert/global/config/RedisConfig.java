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
 * 순번과 입장 토큰은 noeviction Redis, 분산 잠금과 좌석 캐시는 LRU Redis를 사용합니다.
 * 통신 실패 시 대기열은 DB 결과로 보완하고 잠금과 캐시는 건너뜁니다.
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
