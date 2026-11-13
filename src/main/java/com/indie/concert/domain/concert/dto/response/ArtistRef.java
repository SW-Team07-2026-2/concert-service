package com.indie.concert.domain.concert.dto.response;

/** 라인업 한 명 — 목록 순서가 라인업 순서 (첫 번째 = 헤드라이너) · genre 는 Catalog Genre 코드 */
public record ArtistRef(long id, String name, String genre) {}
