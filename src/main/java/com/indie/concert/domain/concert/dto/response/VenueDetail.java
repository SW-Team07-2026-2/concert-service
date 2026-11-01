package com.indie.concert.domain.concert.dto.response;

import java.util.List;

public record VenueDetail(long id, String name, String region, String address, Integer capacity,
                          List<UpcomingConcert> upcomingConcerts) {}
