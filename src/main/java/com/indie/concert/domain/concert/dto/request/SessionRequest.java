package com.indie.concert.domain.concert.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;

/**
 * 회차 (11/27) — 등록은 startsAt 만. 수정은 목록 전체를 보냅니다: id 가 있으면 그 회차의 시각을 바꾸고 · 없으면 새 회차 ·
 * 목록에 없는 기존 회차는 지웁니다 (예매 기록이 있으면 409 SESSION_HAS_BOOKINGS).
 */
public record SessionRequest(@Positive Long id, @NotNull OffsetDateTime startsAt) {}
