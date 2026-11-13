package com.indie.concert.domain.concert.repository;

import java.util.Collection;
import java.util.List;

/** 라인업 저장소 포트 (concert_artist) — 구현은 ConcertArtistJpaRepository */
public interface ConcertArtistRepository {

    /** 공연들의 라인업 + 아티스트 이름 · 장르 — 공연 id · 라인업 순서대로 */
    List<LineupRow> findLineups(Collection<Long> concertIds);

    int deleteLineup(long concertId);

    /** artistIds 순서가 라인업 순서 (1 = 헤드라이너) — 한 문장으로 넣습니다 */
    int insertLineup(long concertId, Long[] artistIds);

    interface LineupRow {
        Long getConcertId();
        Long getArtistId();
        String getName();
        String getGenre();
    }
}
