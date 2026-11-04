package com.indie.concert.domain.ingest.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * artist_id 는 Catalog id — 수집기는 Catalog I4 응답의 id 를 씁니다. 이름 · 장르는 그때 함께 복제합니다 (FR-CON-31).
 * starts_at · ends_at · booking_open_at 은 오프셋을 붙여 보냅니다 (2026-11-20T19:00:00+09:00). ends_at 은 원문에 없으면 null.
 * price 는 원문에 없으면 null (I14 — 추측 금지). queue_enabled 가 null 이면 기존 값(새 공연은 true).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ConcertUpsert(@NotBlank @Size(max = 32) String source,
                            @NotBlank @Size(max = 128) String externalId,
                            @Size(max = 128) String sourceHash,
                            @NotNull @Positive Long artistId,
                            @NotBlank @Size(max = 100) String artistName,
                            @Size(max = 50) String artistGenre,
                            @NotBlank @Size(max = 200) String title,
                            @NotNull ConcertEntity.Type type,
                            @NotNull OffsetDateTime startsAt,
                            OffsetDateTime endsAt,
                            OffsetDateTime bookingOpenAt,
                            @PositiveOrZero Integer price,
                            Boolean queueEnabled,
                            @NotNull @Valid VenueUpsert venue) {}
