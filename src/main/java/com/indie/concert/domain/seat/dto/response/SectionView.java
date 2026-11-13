package com.indie.concert.domain.seat.dto.response;

import java.math.BigDecimal;
import java.util.List;

/** shape — 꼭짓점 [[x, y], ...] · angle — 무대 기준 기울기(도). 이 공연에서 파는 좌석이 없는 구역은 seats 가 빈 목록 */
public record SectionView(long id, String name, List<List<BigDecimal>> shape, BigDecimal angle, List<SeatView> seats) {}
