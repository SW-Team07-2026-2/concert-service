package com.indie.concert.domain.ingest.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** I15 — 공연은 한 종류라 type 이 없습니다 (Catalog 의 I15 와 차이) */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DiffRequest(@NotBlank @Size(max = 32) String source,
                          @NotEmpty @Size(max = 1000) List<@Valid @NotNull DiffItem> items) {}
