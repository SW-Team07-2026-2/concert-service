package com.indie.concert.domain.layout.dto.response;

/** 저장된 구역 — seatCount 는 새로 넣은 좌석 수 (같은 구역을 다시 보내면 이전 좌석을 지우고 새로 넣습니다) */
public record SectionSaved(long venueId, long floorId, long sectionId, int seatCount) {}
