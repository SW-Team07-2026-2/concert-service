package com.indie.concert.domain.concert.repository;

import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.Region;
import com.indie.concert.global.common.util.ListSort;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 공연 저장소 포트 - 구현은 ConcertJpaRepository */
public interface ConcertRepository {

    Optional<ConcertEntity> findById(Long id);

    List<ConcertEntity> findAllById(Iterable<Long> ids);

    /** 관리자 등록 - 같은 수집 공연이 이미 있나 */
    Optional<ConcertEntity> findBySourceAndExternalId(String source, String externalId);

    /** 공연 목록 - 조건은 전부 선택 (null 이면 무시). 정렬은 pageable 이 정합니다 */
    Slice<ConcertEntity> search(Filter filter, Pageable pageable);

    /** 캘린더 - 회차가 [from, to) 에 걸치는 공연 (첫 회차 < to, 마지막 회차 >= from), artistIds 가 비어 있으면 전체 (라인업에 한 명이라도 있으면 포함) */
    List<ConcertEntity> findForCalendar(OffsetDateTime from, OffsetDateTime to, Collection<Long> artistIds);

    /** 팔로우 탭 업데이트순 (Catalog 내부 호출) - 아티스트별 그 아티스트가 나오는 공연의 가장 최근 등록, 수정 시각, [artistId, max(updated_at)] */
    List<Object[]> findLatestUpdatedByArtist(Collection<Long> artistIds);

    /** 공연장 상세 - 공연장의 예정 공연 (아직 시작하지 않은 회차가 남은 공연) */
    List<ConcertEntity> findByVenueIdAndLastStartsAtGreaterThanEqualOrderByStartsAtAsc(Long venueId, OffsetDateTime now, Limit limit);

    /** 대기열 입장, 스냅샷 대상 - 대기열을 켠 판매 중 공연 (마지막 회차 시작 전) */
    List<ConcertEntity> findQueueActive(OffsetDateTime now);

    /** artist.updated - 그 아티스트의 단독 공연 장르를 아티스트 장르로. 페스티벌은 관리자 값이라 건드리지 않습니다. 바뀐 행 수 */
    int updateSoloGenre(long artistId, String genre);

    /** 공연장을 바꿔도 되나 - 이미 만든 공연 좌석은 그 공연장 배치를 가리킵니다 */
    boolean hasSeats(long concertId);

    ConcertEntity save(ConcertEntity concert);

    /**
     * 회차가 from 이상, to 미만 구간에 걸치는 공연의 조회 조건입니다.
     * 빈 필터는 무시하고 ARTIST_ORDER 정렬은 전달한 아티스트 순서를 사용합니다.
     */
    record Filter(OffsetDateTime from, OffsetDateTime to, List<Region> regions, List<String> genres, List<ConcertEntity.Type> types,
                  List<Long> artistIds, String q, List<ConcertEntity.SaleStatus> saleStatuses, OffsetDateTime now, ListSort sort) {}
}
