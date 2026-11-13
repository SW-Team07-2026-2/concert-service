package com.indie.concert.domain.concert.repository;

import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.Region;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 공연 저장소 포트 — 구현은 ConcertJpaRepository */
public interface ConcertRepository {

    Optional<ConcertEntity> findById(Long id);

    List<ConcertEntity> findAllById(Iterable<Long> ids);

    /** 관리자 등록 — 같은 수집 공연이 이미 있나 */
    Optional<ConcertEntity> findBySourceAndExternalId(String source, String externalId);

    /** 공연 목록 — 조건은 전부 선택 (null 이면 무시). 정렬은 pageable 이 정합니다 */
    Slice<ConcertEntity> search(Filter filter, Pageable pageable);

    /** 캘린더 — 시작 시각 [from, to) · artistIds 가 비어 있으면 전체 (라인업에 한 명이라도 있으면 포함) */
    List<ConcertEntity> findForCalendar(OffsetDateTime from, OffsetDateTime to, Collection<Long> artistIds);

    /** 공연장 상세 — 공연장의 예정 공연 */
    List<ConcertEntity> findByVenueIdAndStartsAtGreaterThanEqualOrderByStartsAtAsc(Long venueId, OffsetDateTime now, Limit limit);

    /** 검표 — 입장이 열린 공연: 시작이 opensBy 이전이고, 끝 시각이 now 이후 (끝 시각이 없으면 시작이 startedAfter 이후) */
    List<ConcertEntity> findEntryOpen(OffsetDateTime now, OffsetDateTime opensBy, OffsetDateTime startedAfter);

    /** 대기열 입장 · 스냅샷 대상 — 대기열을 켠 판매 중 공연 */
    List<ConcertEntity> findQueueActive(OffsetDateTime now);

    /** artist.updated — 그 아티스트의 단독 공연 장르를 아티스트 장르로. 페스티벌은 관리자 값이라 건드리지 않습니다. 바뀐 행 수 */
    int updateSoloGenre(long artistId, String genre);

    /** 공연장을 바꿔도 되나 — 이미 만든 공연 좌석은 그 공연장 배치를 가리킵니다 */
    boolean hasSeats(long concertId);

    ConcertEntity save(ConcertEntity concert);

    /** from 이상 · to 미만. region 은 공연장 지역 · genre 는 공연 장르 · artistId 는 라인업에 있는 공연 */
    record Filter(OffsetDateTime from, OffsetDateTime to, Region region, String genre, ConcertEntity.Type type, Long artistId) {}
}
