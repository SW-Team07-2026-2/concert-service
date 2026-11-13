package com.indie.concert.domain.concert.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * artist — Catalog 아티스트 복제 (artist.updated 로 채움 · id 는 catalog.artist.id 그대로).
 * 라인업 API 는 id 만 받고 이름 · 장르는 여기서 읽습니다 — 공연 조회에 Catalog 호출이 없습니다.
 * 쓰기는 ArtistRepository.upsert 한 문장으로만 합니다.
 */
@Entity
@Table(name = "artist")
public class ArtistEntity {

    @Id
    private Long id;

    private String name;

    /** Catalog Genre 코드 (INDIE_ROCK …) · 없으면 null */
    private String genre;

    private OffsetDateTime updatedAt;

    protected ArtistEntity() {}

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getGenre() { return genre; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
