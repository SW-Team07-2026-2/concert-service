package com.indie.concert.domain.booking.dto.response;

/** id = 공연 좌석 id · floor '1F' · section 'A' · row · number 는 1 부터 */
public record SeatRef(long id, String floor, String section, int row, int number, int price) {}
