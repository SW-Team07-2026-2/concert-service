package com.indie.concert.domain.concert.repository;

import com.indie.concert.domain.concert.entity.ConcertArtistEntity;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.Region;
import com.indie.concert.domain.concert.entity.VenueEntity;
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
        return findAll(Specification.where(startsFrom(f.from())).and(startsBefore(f.to())).and(inRegion(f.region()))
                .and(equalTo("genre", f.genre())).and(equalTo("type", f.type()))
                .and(withArtists(f.artistId() == null ? null : List.of(f.artistId()))), pageable);
    }

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

    private static Specification<ConcertEntity> inRegion(Region region) {
        if (region == null) return null;
        return (root, query, cb) -> {
            Subquery<Long> venues = query.subquery(Long.class);
            Root<VenueEntity> v = venues.from(VenueEntity.class);
            venues.select(v.get("id")).where(cb.equal(v.get("region"), region));
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

    private static Specification<ConcertEntity> equalTo(String attribute, Object value) {
        if (value == null || value instanceof String s && s.isBlank()) return null;
        return (root, query, cb) -> cb.equal(root.get(attribute), value);
    }
}
