package com.indie.concert.domain.layout.repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * 공연장 배치 저장소 포트 (floor · section · seat) — 구현은 LayoutJpaRepository.
 * 무대 그리기 결과는 구역 단위로 들어와 한 트랜잭션에서 층 → 구역 → 좌석 순서로 저장합니다.
 */
public interface LayoutRepository {

    /** 층을 이름으로 찾고 없으면 만듭니다 — 반환은 floor.id */
    long upsertFloor(long venueId, String name);

    /** 구역을 이름으로 찾고 없으면 만듭니다. 있으면 모양 · 기울기를 덮어씁니다 — 반환은 section.id */
    long upsertSection(long floorId, String name, String shapeJson, BigDecimal angle);

    /** 이 구역 좌석을 파는 공연이 있나 (concert_seat) — 있으면 배치를 바꿀 수 없습니다 */
    boolean sectionInUse(long sectionId);

    int deleteSeats(long sectionId);

    /** 좌석을 한 문장으로 넣습니다 — 배열은 같은 길이 · 같은 순서 */
    int insertSeats(long sectionId, Integer[] rows, Integer[] numbers, BigDecimal[] xs, BigDecimal[] ys, BigDecimal[] angles);

    /** 좌석 화면 — 공연장의 층 · 구역 (층 이름 · 구역 이름 순) */
    List<SectionRow> findSections(long venueId);

    interface SectionRow {
        Long getFloorId();
        String getFloorName();
        Long getId();
        String getName();
        String getShape();
        BigDecimal getAngle();
    }
}
