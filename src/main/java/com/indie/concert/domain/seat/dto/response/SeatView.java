package com.indie.concert.domain.seat.dto.response;

import java.math.BigDecimal;

/** id = 공연 좌석 id (예매 요청의 seatIds) · status: AVAILABLE · HELD · SOLD */
public record SeatView(long id, int row, int number, BigDecimal x, BigDecimal y, BigDecimal angle, int price, String status) {}
