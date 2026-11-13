package com.indie.concert.domain.concert.repository;

import com.indie.concert.domain.concert.entity.ArtistEntity;

import java.time.OffsetDateTime;
import java.util.List;

/** 아티스트 복제 저장소 포트 — 구현은 ArtistJpaRepository */
public interface ArtistRepository {

    List<ArtistEntity> findAllById(Iterable<Long> ids);

    /**
     * artist.updated 반영 — 없으면 만들고, 있으면 더 새로운 이벤트(updatedAt)일 때만 덮어씁니다 (재전달된 옛 이벤트 무시).
     * 반환 1 = 반영 · 0 = 더 옛 이벤트
     */
    int upsert(long id, String name, String genre, OffsetDateTime updatedAt);
}
