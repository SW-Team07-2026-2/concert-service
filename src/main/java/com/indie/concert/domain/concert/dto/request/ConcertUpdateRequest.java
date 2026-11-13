package com.indie.concert.domain.concert.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * 공연 수정 — 보낸 값만 바꿉니다 (null 은 그대로). 종류 · 수집 출처는 바꿀 수 없고, 라인업은 PUT .../lineup 으로.
 * genre — FESTIVAL 만. venueId — 공연 좌석을 만들기 전에만 (만든 뒤면 409).
 */
public record ConcertUpdateRequest(@Positive Long venueId,
                                   @Size(min = 1, max = 200) String title,
                                   @Size(max = 32) @Pattern(regexp = "[A-Z][A-Z_]*") String genre,
                                   OffsetDateTime startsAt,
                                   OffsetDateTime endsAt,
                                   @PositiveOrZero Integer price,
                                   OffsetDateTime bookingOpenAt,
                                   Boolean queueEnabled,
                                   @Min(1) @Max(10) Integer maxSeatsPerUser) {}
