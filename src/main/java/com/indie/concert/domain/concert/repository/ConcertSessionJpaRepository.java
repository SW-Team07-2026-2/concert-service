package com.indie.concert.domain.concert.repository;

import com.indie.concert.domain.concert.entity.ConcertSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConcertSessionJpaRepository extends JpaRepository<ConcertSessionEntity, Long>, ConcertSessionRepository {
}
