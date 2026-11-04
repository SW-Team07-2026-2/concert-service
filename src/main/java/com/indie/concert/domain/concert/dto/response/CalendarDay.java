package com.indie.concert.domain.concert.dto.response;

import java.time.LocalDate;
import java.util.List;

public record CalendarDay(LocalDate date, List<ConcertSummary> concerts) {}
