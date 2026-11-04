package com.indie.concert.domain.concert.repository;

import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.Region;
import com.indie.concert.global.common.util.SourceHash;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 공연 저장소 포트 — 구현은 ConcertJpaRepository. (source, external_id) 가 수집 멱등 upsert 기준 */
public interface ConcertRepository {

    Optional<ConcertEntity> findById(Long id);

    List<ConcertEntity> findAllById(Iterable<Long> ids);

    Optional<ConcertEntity> findBySourceAndExternalId(String source, String externalId);

    /** N1 — 조건은 전부 선택 (null 이면 무시). 정렬은 pageable 이 정합니다 */
    Slice<ConcertEntity> search(Filter filter, Pageable pageable);

    /** N3 — 시작 시각 [from, to) · artistIds 가 비어 있으면 전체 */
    List<ConcertEntity> findForCalendar(OffsetDateTime from, OffsetDateTime to, Collection<Long> artistIds);

    /** N15 — 공연장의 예정 공연 (같은 스키마라 JOIN 으로 끝납니다) */
    List<ConcertEntity> findByVenueIdAndStartsAtGreaterThanEqualOrderByStartsAtAsc(Long venueId, OffsetDateTime now, Limit limit);

    /** 검표 — 입장이 열린 공연 (시작 시각 [from, to]) */
    List<ConcertEntity> findByStartsAtBetweenOrderByStartsAtAsc(OffsetDateTime from, OffsetDateTime to);

    /** 대기열 입장 · 스냅샷 대상 — 대기열을 켠 판매 중 공연 */
    List<ConcertEntity> findQueueActive(OffsetDateTime now);

    List<SourceHash> findHashes(String source, Collection<String> externalIds);

    /** artist.updated — 그 아티스트의 공연 전부에 이름 · 장르 반영. genre 가 null 이면 기존 값을 둡니다. 바뀐 행 수 */
    int updateArtist(long artistId, String name, String genre);

    ConcertEntity save(ConcertEntity concert);

    /** from 이상 · to 미만. region 은 공연장 지역 · genre 는 복제된 아티스트 장르 */
    record Filter(OffsetDateTime from, OffsetDateTime to, Region region, String genre, ConcertEntity.Type type, Long artistId) {}
}
