package com.indie.concert.domain.seat.dto.response;

import java.util.List;

public record SeatMap(long concertId, long total, long available, List<SeatView> seats) {}
