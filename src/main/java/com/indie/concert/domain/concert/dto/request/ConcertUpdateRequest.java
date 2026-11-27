package com.indie.concert.domain.concert.dto.request;

import com.indie.concert.domain.concert.entity.Genre;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 공연 수정 요청. null 필드는 유지하며 종류와 수집 출처는 변경하지 않습니다.
 * 회차 목록과 이미지 목록은 전달하면 전체 교체합니다. startsAt 단독 변경은 회차가 하나일 때만 허용합니다.
 */
public record ConcertUpdateRequest(@Positive Long venueId,
                                   @Size(min = 1, max = 200) String title,
                                   Genre genre,
                                   OffsetDateTime startsAt,
                                   OffsetDateTime endsAt,
                                   @PositiveOrZero Integer price,
                                   OffsetDateTime bookingOpenAt,
                                   Boolean queueEnabled,
                                   @Min(1) @Max(10) Integer maxSeatsPerUser,
                                   @Size(max = 20000) String description,
                                   @Size(max = 20000) String terms,
                                   @Size(max = 5000) String requirements,
                                   @Size(max = 512) String posterUrl,
                                   @Size(max = 20) List<@NotBlank @Size(max = 512) String> imageUrls,
                                   @Size(min = 1, max = 50) List<@NotNull @Valid SessionRequest> sessions) {}
