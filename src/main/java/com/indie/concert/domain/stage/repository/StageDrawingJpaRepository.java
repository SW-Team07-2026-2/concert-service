package com.indie.concert.domain.stage.repository;

import com.indie.concert.domain.stage.entity.StageDrawingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StageDrawingJpaRepository extends JpaRepository<StageDrawingEntity, Long>, StageDrawingRepository {
}
