package com.indie.concert.domain.seat.dto.response;

public record SeatView(long id, String section, String row, int number, int price, String status) {}
