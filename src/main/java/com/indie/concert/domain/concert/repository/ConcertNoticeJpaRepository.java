package com.indie.concert.domain.concert.repository;

import com.indie.concert.domain.concert.entity.ConcertNoticeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConcertNoticeJpaRepository extends JpaRepository<ConcertNoticeEntity, Long>, ConcertNoticeRepository {
}
