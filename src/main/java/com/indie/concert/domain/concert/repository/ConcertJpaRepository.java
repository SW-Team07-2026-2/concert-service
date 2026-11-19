package com.indie.concert.domain.concert.repository;

import com.indie.concert.domain.concert.entity.ArtistEntity;
import com.indie.concert.domain.concert.entity.ConcertArtistEntity;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.Region;
import com.indie.concert.domain.concert.entity.VenueEntity;
import com.indie.concert.global.common.util.ListSort;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface ConcertJpaRepository extends JpaRepository<ConcertEntity, Long>, JpaSpecificationExecutor<ConcertEntity>,
        ConcertRepository {

    @Override
    default Slice<ConcertEntity> search(Filter f, Pageable pageable) {
        return findAll(Specification.where(startsFrom(f.from())).and(startsBefore(f.to())).and(inRegion(f.regions()))
                .and(genreIn(f.genres())).and(in("type", f.types())).and(withArtists(f.artistIds()))
                .and(titleArtistOrVenue(f.q())).and(saleStatusIn(f.saleStatuses(), f.now()))
                .and(f.sort() == ListSort.ARTIST_ORDER ? byArtistOrder(f.artistIds()) : null), pageable);
    }

    @Override
    @Query("select ca.artistId, max(c.updatedAt) from ConcertArtistEntity ca, ConcertEntity c "
            + "where c.id = ca.concertId and ca.artistId in :artistIds group by ca.artistId")
    List<Object[]> findLatestUpdatedByArtist(@Param("artistIds") Collection<Long> artistIds);

    @Override
    default List<ConcertEntity> findForCalendar(OffsetDateTime from, OffsetDateTime to, Collection<Long> artistIds) {
        return findAll(Specification.where(startsFrom(from)).and(startsBefore(to)).and(withArtists(artistIds)),
                Sort.by("startsAt").ascending().and(Sort.by("id").ascending()));
    }

    @Override
    @Query("select c from ConcertEntity c where c.startsAt <= :opensBy "
            + "and (c.endsAt >= :now or (c.endsAt is null and c.startsAt >= :startedAfter)) order by c.startsAt asc")
    List<ConcertEntity> findEntryOpen(@Param("now") OffsetDateTime now, @Param("opensBy") OffsetDateTime opensBy,
                                      @Param("startedAfter") OffsetDateTime startedAfter);

    @Override
    @Query("select c from ConcertEntity c where c.queueEnabled = true and c.bookingOpenAt <= :now and c.startsAt > :now")
    List<ConcertEntity> findQueueActive(@Param("now") OffsetDateTime now);

    /** idx_concert_artist_artist 로 그 아티스트 공연만 · genre 는 null 일 수 있어 타입을 적어 둡니다 */
    @Override
    @Modifying
    @Query(value = """
            UPDATE concert c SET genre = CAST(:genre AS VARCHAR), updated_at = now()
            WHERE c.type = 'SOLO' AND c.genre IS DISTINCT FROM CAST(:genre AS VARCHAR)
              AND c.id IN (SELECT ca.concert_id FROM concert_artist ca WHERE ca.artist_id = :artistId)
            """, nativeQuery = true)
    int updateSoloGenre(@Param("artistId") long artistId, @Param("genre") String genre);

    @Override
    @Query(value = "SELECT EXISTS (SELECT 1 FROM concert_seat WHERE concert_id = :concertId)", nativeQuery = true)
    boolean hasSeats(@Param("concertId") long concertId);

    private static Specification<ConcertEntity> startsFrom(OffsetDateTime from) {
        if (from == null) return null;
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("startsAt"), from);
    }

    private static Specification<ConcertEntity> startsBefore(OffsetDateTime to) {
        if (to == null) return null;
        return (root, query, cb) -> cb.lessThan(root.get("startsAt"), to);
    }

    private static Specification<ConcertEntity> inRegion(Collection<Region> regions) {
        if (regions == null || regions.isEmpty()) return null;
        return (root, query, cb) -> {
            Subquery<Long> venues = query.subquery(Long.class);
            Root<VenueEntity> v = venues.from(VenueEntity.class);
            venues.select(v.get("id")).where(v.get("region").in(regions));
            return root.get("venueId").in(venues);
        };
    }

    /** 라인업에 한 명이라도 있는 공연 (concert_artist · idx_concert_artist_artist) */
    private static Specification<ConcertEntity> withArtists(Collection<Long> artistIds) {
        if (artistIds == null || artistIds.isEmpty()) return null;
        return (root, query, cb) -> {
            Subquery<Long> lineup = query.subquery(Long.class);
            Root<ConcertArtistEntity> ca = lineup.from(ConcertArtistEntity.class);
            lineup.select(ca.get("concertId")).where(ca.get("artistId").in(artistIds));
            return root.get("id").in(lineup);
        };
    }

    private static Specification<ConcertEntity> in(String attribute, Collection<?> values) {
        if (values == null || values.isEmpty()) return null;
        return (root, query, cb) -> root.get(attribute).in(values);
    }

    /**
     * 11/19 장르 — 공연 장르이거나 라인업 아티스트 중 누군가의 대표 장르 (프론트 보완 기준 4장).
     * 아티스트 복제본은 대표 장르(Catalog 장르 목록의 첫 번째)만 가지므로 두 번째 장르로는 걸리지 않습니다 (사용자 결정 · 스키마 그대로)
     */
    private static Specification<ConcertEntity> genreIn(Collection<String> genres) {
        if (genres == null || genres.isEmpty()) return null;
        return (root, query, cb) -> {
            Subquery<Long> lineup = query.subquery(Long.class);
            Root<ConcertArtistEntity> ca = lineup.from(ConcertArtistEntity.class);
            Subquery<Long> artists = lineup.subquery(Long.class);
            Root<ArtistEntity> a = artists.from(ArtistEntity.class);
            artists.select(a.get("id")).where(a.get("genre").in(genres));
            lineup.select(ca.get("concertId")).where(ca.get("artistId").in(artists));
            return cb.or(root.get("genre").in(genres), root.get("id").in(lineup));
        };
    }

    /** 검색어 LIKE (대소문자 무시 · 검색어의 % _ 는 글자 그대로) — 공연 제목 · 라인업 아티스트 이름 · 11/19 공연장 이름 (프론트 요구 2-2) */
    private static Specification<ConcertEntity> titleArtistOrVenue(String q) {
        if (q == null || q.isBlank()) return null;
        String pattern = "%" + q.strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return (root, query, cb) -> {
            Subquery<Long> lineup = query.subquery(Long.class);
            Root<ConcertArtistEntity> ca = lineup.from(ConcertArtistEntity.class);
            Subquery<Long> named = lineup.subquery(Long.class);
            Root<ArtistEntity> a = named.from(ArtistEntity.class);
            named.select(a.get("id")).where(cb.like(cb.lower(a.get("name")), pattern, '\\'));
            lineup.select(ca.get("concertId")).where(ca.get("artistId").in(named));
            Subquery<Long> venue = query.subquery(Long.class);
            Root<VenueEntity> v = venue.from(VenueEntity.class);
            venue.select(v.get("id")).where(cb.like(cb.lower(v.get("name")), pattern, '\\'));
            return cb.or(cb.like(cb.lower(root.get("title")), pattern, '\\'), root.get("id").in(lineup), root.get("venueId").in(venue));
        };
    }

    /** 진행 현황 — ConcertEntity.saleStatus(now) 와 같은 기준 (시작 이후 CLOSED · 오픈 전이나 미정 SCHEDULED · 그 사이 ON_SALE) */
    private static Specification<ConcertEntity> saleStatusIn(Collection<ConcertEntity.SaleStatus> statuses, OffsetDateTime now) {
        if (statuses == null || statuses.isEmpty()) return null;
        return (root, query, cb) -> {
            Path<OffsetDateTime> starts = root.get("startsAt");
            Path<OffsetDateTime> opens = root.get("bookingOpenAt");
            return cb.or(statuses.stream().map(s -> switch (s) {
                case CLOSED -> cb.lessThanOrEqualTo(starts, now);
                case SCHEDULED -> cb.and(cb.greaterThan(starts, now), cb.or(cb.isNull(opens), cb.greaterThan(opens, now)));
                case ON_SALE -> cb.and(cb.greaterThan(starts, now), cb.lessThanOrEqualTo(opens, now));
            }).toArray(Predicate[]::new));
        };
    }

    /**
     * 넘긴 아티스트 순서대로 — 공연마다 라인업 중 가장 앞 순서의 자리. 같으면 공연일 가까운 순.
     * 정렬은 데이터 조회에만 겁니다 (페이지의 개수 조회는 결과 타입이 Long). pageable 은 정렬 없이 넘어와야 합니다
     */
    private static Specification<ConcertEntity> byArtistOrder(List<Long> artistIds) {
        return (root, query, cb) -> {
            if (!Long.class.equals(query.getResultType())) {
                Subquery<Integer> rank = query.subquery(Integer.class);
                Root<ConcertArtistEntity> ca = rank.from(ConcertArtistEntity.class);
                CriteriaBuilder.SimpleCase<Long, Integer> position = cb.selectCase(ca.get("artistId"));
                for (int i = 0; i < artistIds.size(); i++) {
                    position.when(artistIds.get(i), i);
                }
                rank.select(cb.min(position.otherwise(artistIds.size()))).where(cb.equal(ca.get("concertId"), root.get("id")));
                query.orderBy(cb.asc(rank), cb.asc(root.get("startsAt")), cb.asc(root.get("id")));
            }
            return null;
        };
    }
}
