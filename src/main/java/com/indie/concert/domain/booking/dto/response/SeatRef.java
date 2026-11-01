package com.indie.concert.domain.booking.dto.response;

public record SeatRef(long id, String section, String row, int number, int price) {}
