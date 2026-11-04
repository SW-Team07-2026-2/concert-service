package com.indie.concert.domain.concert.dto.response;

import java.util.List;

public record ConcertCalendar(String month, List<CalendarDay> days) {}
