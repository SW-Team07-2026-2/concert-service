package com.indie.concert.domain.stage.repository;

import com.indie.concert.domain.stage.entity.StageDrawingEntity;

import java.util.Optional;

/** 무대 그리기 저장소 포트 — 구현은 StageDrawingJpaRepository */
public interface StageDrawingRepository {

    Optional<StageDrawingEntity> findById(Long venueId);

    StageDrawingEntity save(StageDrawingEntity drawing);
}
