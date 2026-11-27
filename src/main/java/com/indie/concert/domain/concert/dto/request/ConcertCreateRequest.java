package com.indie.concert.domain.concert.dto.request;

import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.Genre;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 관리자가 검수한 공연 등록 요청. 라인업 순서의 첫 아티스트가 헤드라이너입니다.
 * 회차 목록을 보내면 그 첫 시각을 대표 시작 시각으로 사용하고 없으면 startsAt으로 회차 하나를 만듭니다.
 * 대표 가격과 좌석별 가격은 별도로 관리합니다.
 */
public record ConcertCreateRequest(@NotNull @Positive Long venueId,
                                   @NotBlank @Size(max = 200) String title,
                                   @NotNull ConcertEntity.Type type,
                                   Genre genre,
                                   OffsetDateTime startsAt,
                                   OffsetDateTime endsAt,
                                   @PositiveOrZero Integer price,
                                   OffsetDateTime bookingOpenAt,
                                   Boolean queueEnabled,
                                   @NotNull @Min(1) @Max(10) Integer maxSeatsPerUser,
                                   @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> artistIds,
                                   @Size(max = 32) String source,
                                   @Size(max = 128) String externalId,
                                   @Size(max = 20000) String description,
                                   @Size(max = 20000) String terms,
                                   @Size(max = 5000) String requirements,
                                   @Size(max = 512) String posterUrl,
                                   @Size(max = 20) List<@NotBlank @Size(max = 512) String> imageUrls,
                                   @Size(min = 1, max = 50) List<@NotNull @Valid SessionRequest> sessions) {}
