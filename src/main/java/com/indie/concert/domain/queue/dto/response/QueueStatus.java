package com.indie.concert.domain.queue.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/** N4 · N5 응답 — state: WAITING · ADMITTED · EXPIRED. degraded 는 Redis 복구 중 (DB 로 계산한 순번) */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueueStatus(String state, Long rank, Long total, Long etaSeconds, Long pollAfterMs, Boolean degraded,
                          String entryToken, Long expiresIn) {}
