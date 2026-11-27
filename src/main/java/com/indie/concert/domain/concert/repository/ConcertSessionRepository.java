package com.indie.concert.domain.concert.repository;

import com.indie.concert.domain.concert.entity.ConcertSessionEntity;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 회차 저장소 포트 - 구현은 ConcertSessionJpaRepository */
public interface ConcertSessionRepository {

    Optional<ConcertSessionEntity> findById(Long id);

    List<ConcertSessionEntity> findAllById(Iterable<Long> ids);

    /** 공연의 회차 - 시작 순 (uq_concert_session) */
    List<ConcertSessionEntity> findByConcertIdOrderByStartsAtAsc(Long concertId);

    /** 여러 공연의 회차 - 공연 목록, 캘린더, 예매 목록 */
    List<ConcertSessionEntity> findByConcertIdInOrderByStartsAtAsc(Collection<Long> concertIds);

    /**
     * 검표 - 지금 입장이 열린 회차: 시작이 opensBy(지금 + 공개 시작 분) 이하이고 startedAfter(지금 - 마감 분) 보다 뒤.
     * 입장 시간 = [시작 - 공개 시작 분, 시작 + 마감 분)
     */
    List<ConcertSessionEntity> findByStartsAtLessThanEqualAndStartsAtGreaterThanOrderByStartsAtAsc(OffsetDateTime opensBy,
                                                                                                 OffsetDateTime startedAfter);

    ConcertSessionEntity save(ConcertSessionEntity session);

    void delete(ConcertSessionEntity session);
}
