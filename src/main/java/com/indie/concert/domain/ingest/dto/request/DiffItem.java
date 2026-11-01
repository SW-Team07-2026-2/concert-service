package com.indie.concert.domain.ingest.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** etag 는 받아도 쓰지 않습니다 — 조건부 요청은 수집기 쪽 일 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DiffItem(@NotBlank @Size(max = 128) String externalId, String etag, @Size(max = 128) String sourceHash) {}
