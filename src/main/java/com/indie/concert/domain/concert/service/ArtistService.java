package com.indie.concert.domain.concert.service;

import com.indie.concert.domain.concert.entity.Genre;
import com.indie.concert.domain.concert.repository.ArtistRepository;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/** artist 복제 — artist.updated 를 반영하고, 그 아티스트의 단독 공연 장르를 같이 바꿉니다 (페스티벌은 관리자 값 그대로) */
@Service
public class ArtistService {

    private static final Logger log = LoggerFactory.getLogger(ArtistService.class);

    private final ArtistRepository artistRepository;
    private final ConcertRepository concertRepository;

    public ArtistService(ArtistRepository artistRepository, ConcertRepository concertRepository) {
        this.artistRepository = artistRepository;
        this.concertRepository = concertRepository;
    }

    @Transactional
    public void apply(long artistId, String name, String genreCode, OffsetDateTime occurredAt) {
        // 장르는 Catalog 의 목록과 같아야 합니다 (ck_artist_genre). 모르는 값이 오면 복제를 멈추지 않고 장르 없음으로 둡니다 — Genre 를 Catalog 와 맞춰야 합니다
        Genre parsed = Genre.parse(genreCode);
        if (genreCode != null && parsed == null) log.warn("artist_genre_unknown artistId={} genre={}", artistId, genreCode);
        String genre = parsed == null ? null : parsed.name();
        if (artistRepository.upsert(artistId, name.strip(), genre, occurredAt) == 0) {
            log.info("artist_event_stale artistId={} occurredAt={}", artistId, occurredAt);
            return;
        }
        int concerts = concertRepository.updateSoloGenre(artistId, genre);
        if (concerts > 0) log.info("solo_concert_genre_updated artistId={} genre={} concerts={}", artistId, genre, concerts);
    }
}
