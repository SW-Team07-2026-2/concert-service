package com.indie.concert.domain.ingest.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 내부 수집 API(I7 · I15) 요청 · 응답. 호출자가 파이썬 수집 CronJob 이라 snake_case 입니다 (Catalog 내부 API 와 같은 규칙).
 * 한 요청 최대 500건 (08 §10.2).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ConcertBatch(@NotEmpty @Size(max = 500) List<@Valid @NotNull ConcertUpsert> items) {}
