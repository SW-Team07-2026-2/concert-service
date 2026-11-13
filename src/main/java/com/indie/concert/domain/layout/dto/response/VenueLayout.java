package com.indie.concert.domain.layout.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * 공연장에 저장된 배치 — 층 → 구역(모양 · 기울기) → 좌석(위치). 관리자 화면용입니다 (무대 탭의 현재 배치 · 공연 탭의 좌석 가격).
 * 좌석 id 는 seat.id — 좌석 가격 API(POST /internal/admin/concerts/{id}/seats)의 seatId 입니다 (공개 좌석 화면의 id 는 공연 좌석 id 라 다릅니다).
 */
public record VenueLayout(long venueId, List<Floor> floors) {

    public record Floor(long id, String name, List<Section> sections) {}

    public record Section(long id, String name, List<List<BigDecimal>> shape, BigDecimal angle, List<Seat> seats) {}

    public record Seat(long id, int row, int number, BigDecimal x, BigDecimal y, BigDecimal angle) {}
}
