package com.indie.concert.domain.seat.dto.response;

import java.util.List;

public record FloorView(long id, String name, List<SectionView> sections) {}
