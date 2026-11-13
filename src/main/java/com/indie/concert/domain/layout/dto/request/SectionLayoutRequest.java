package com.indie.concert.domain.layout.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 무대 그리기 결과 — 구역 하나 (관리자 → GPU 서버 → 관리자가 정제한 JSON 그대로).
 * venueId · venueName 은 확인용입니다 (venueId 가 경로와 다르면 400). 좌석의 seatId('1F-A-1-1')는 저장하지 않습니다 — 층 · 구역 · 열 · 번호로 다시 만들 수 있습니다.
 */
public record SectionLayoutRequest(@Positive Long venueId,
                                   @Size(max = 100) String venueName,
                                   @NotBlank @Size(max = 16) String floor,
                                   @NotNull @Valid SectionShape section,
                                   @NotEmpty @Size(max = 5000) List<@NotNull @Valid SeatPoint> seats) {}
