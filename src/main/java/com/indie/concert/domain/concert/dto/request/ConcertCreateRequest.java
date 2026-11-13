package com.indie.concert.domain.concert.dto.request;

import com.indie.concert.domain.concert.entity.ConcertEntity;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 공연 등록 (관리자 검수 뒤).
 * artistIds — 라인업 순서 (첫 번째 = 헤드라이너) · SOLO 는 1명 · FESTIVAL 은 1명 이상. Catalog 아티스트 id 입니다.
 * genre — FESTIVAL 만 (Catalog Genre 코드). SOLO 는 아티스트 장르를 따르므로 보내면 400.
 * price — 목록에 보여 줄 대표 가격 (좌석별 가격은 POST /internal/admin/concerts/{id}/seats).
 * source · externalId — 수집 데이터로 등록할 때만 둘 다 (같은 수집 공연 두 번 등록 방지).
 */
public record ConcertCreateRequest(@NotNull @Positive Long venueId,
                                   @NotBlank @Size(max = 200) String title,
                                   @NotNull ConcertEntity.Type type,
                                   @Size(max = 32) @Pattern(regexp = "[A-Z][A-Z_]*") String genre,
                                   @NotNull OffsetDateTime startsAt,
                                   OffsetDateTime endsAt,
                                   @PositiveOrZero Integer price,
                                   OffsetDateTime bookingOpenAt,
                                   Boolean queueEnabled,
                                   @NotNull @Min(1) @Max(10) Integer maxSeatsPerUser,
                                   @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> artistIds,
                                   @Size(max = 32) String source,
                                   @Size(max = 128) String externalId) {}
