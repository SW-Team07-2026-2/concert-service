package com.indie.concert.domain.concert.repository;

import com.indie.concert.domain.concert.entity.ConcertNoticeEntity;

import java.util.List;
import java.util.Optional;

/** 공연 변경 공지 저장소 포트 — 구현은 ConcertNoticeJpaRepository */
public interface ConcertNoticeRepository {

    Optional<ConcertNoticeEntity> findByIdAndConcertId(Long id, Long concertId);

    /** 최신순 */
    List<ConcertNoticeEntity> findByConcertIdOrderByIdDesc(Long concertId);

    ConcertNoticeEntity save(ConcertNoticeEntity notice);

    void delete(ConcertNoticeEntity notice);
}
