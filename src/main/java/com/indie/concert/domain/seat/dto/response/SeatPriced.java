package com.indie.concert.domain.seat.dto.response;

/** saved = 새로 만들거나 가격을 바꾼 좌석 · skipped = 선점 · 결제 중 · 판매된 좌석이라 가격을 바꾸지 않은 좌석 */
public record SeatPriced(int saved, int skipped) {}
