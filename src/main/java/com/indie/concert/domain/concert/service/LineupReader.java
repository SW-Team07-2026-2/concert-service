package com.indie.concert.domain.concert.service;

import com.indie.concert.domain.concert.dto.response.ArtistRef;
import com.indie.concert.domain.concert.repository.ConcertArtistRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 공연 여러 개의 라인업을 한 번에 읽습니다 (목록 · 캘린더 · 공연장 상세 — 공연마다 조회하지 않게) */
@Component
public class LineupReader {

    private final ConcertArtistRepository concertArtistRepository;

    public LineupReader(ConcertArtistRepository concertArtistRepository) {
        this.concertArtistRepository = concertArtistRepository;
    }

    /** 공연 id → 라인업 (순서대로). 라인업이 없는 공연은 빈 목록 */
    public Map<Long, List<ArtistRef>> of(Collection<Long> concertIds) {
        Map<Long, List<ArtistRef>> lineups = new HashMap<>();
        if (concertIds.isEmpty()) return lineups;
        for (ConcertArtistRepository.LineupRow r : concertArtistRepository.findLineups(concertIds)) {
            lineups.computeIfAbsent(r.getConcertId(), id -> new ArrayList<>())
                    .add(new ArtistRef(r.getArtistId(), r.getName(), r.getGenre()));
        }
        return lineups;
    }

    public List<ArtistRef> of(long concertId) {
        return of(List.of(concertId)).getOrDefault(concertId, List.of());
    }
}
