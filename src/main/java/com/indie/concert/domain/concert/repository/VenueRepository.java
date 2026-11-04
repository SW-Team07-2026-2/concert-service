package com.indie.concert.domain.concert.repository;

import com.indie.concert.domain.concert.entity.Region;
import com.indie.concert.domain.concert.entity.VenueEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 공연장 저장소 포트 — 구현은 VenueJpaRepository */
public interface VenueRepository {

    Optional<VenueEntity> findById(Long id);

    List<VenueEntity> findAllById(Iterable<Long> ids);

    Optional<VenueEntity> findByName(String name);

    /** N14 — 지역 일치 · 이름 부분 일치 · upcomingFrom 이 있으면 그 이후 공연이 있는 곳만. 전부 선택 */
    Page<VenueEntity> search(Region region, String nameContains, OffsetDateTime upcomingFrom, Pageable pageable);

    VenueEntity save(VenueEntity venue);
}
