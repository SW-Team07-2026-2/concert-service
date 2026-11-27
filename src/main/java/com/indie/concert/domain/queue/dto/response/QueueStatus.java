package com.indie.concert.domain.queue.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * N4, N5 응답 - state: WAITING, ADMITTED, EXPIRED, SOLD_OUT. degraded 는 Redis 복구 중 (DB 로 계산한 순번).
 * pollAfterMs 는 순번이 멀수록 깁니다 (2 ~ 15초) - 프론트는 꼭 이 값대로 다시 부릅니다. SOLD_OUT 이면 조회를 멈춥니다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueueStatus(String state, Long rank, Long total, Long etaSeconds, Long pollAfterMs, Boolean degraded,
                          String entryToken, Long expiresIn) {}
