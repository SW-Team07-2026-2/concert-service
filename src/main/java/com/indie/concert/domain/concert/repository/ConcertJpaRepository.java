package com.indie.concert.domain.concert.repository;

import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.Region;
import com.indie.concert.domain.concert.entity.VenueEntity;
import com.indie.concert.global.common.util.SourceHash;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
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
                .and(equalTo("artistGenre", f.genre())).and(equalTo("type", f.type())).and(equalTo("artistId", f.artistId())),
                pageable);
    }

    @Override
    default List<ConcertEntity> findForCalendar(OffsetDateTime from, OffsetDateTime to, Collection<Long> artistIds) {
        return artistIds == null || artistIds.isEmpty()
                ? findByStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAtAscIdAsc(from, to)
                : findByStartsAtGreaterThanEqualAndStartsAtLessThanAndArtistIdInOrderByStartsAtAscIdAsc(from, to, artistIds);
    }

    List<ConcertEntity> findByStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAtAscIdAsc(OffsetDateTime from,
                                                                                                 OffsetDateTime to);

    List<ConcertEntity> findByStartsAtGreaterThanEqualAndStartsAtLessThanAndArtistIdInOrderByStartsAtAscIdAsc(
            OffsetDateTime from, OffsetDateTime to, Collection<Long> artistIds);

    @Override
    @Query("select c from ConcertEntity c where c.queueEnabled = true and c.bookingOpenAt <= :now and c.startsAt > :now")
    List<ConcertEntity> findQueueActive(@Param("now") OffsetDateTime now);

    @Override
    @Query("select new com.indie.concert.global.common.util.SourceHash(c.externalId, c.sourceHash) "
            + "from ConcertEntity c where c.source = :source and c.externalId in :ids")
    List<SourceHash> findHashes(@Param("source") String source, @Param("ids") Collection<String> externalIds);

    @Override
    @Modifying
    @Query("update ConcertEntity c set c.artistName = :name, c.artistGenre = coalesce(:genre, c.artistGenre) "
            + "where c.artistId = :artistId")
    int updateArtist(@Param("artistId") long artistId, @Param("name") String name, @Param("genre") String genre);

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

    private static Specification<ConcertEntity> equalTo(String attribute, Object value) {
        if (value == null || value instanceof String s && s.isBlank()) return null;
        return (root, query, cb) -> cb.equal(root.get(attribute), value);
    }
}
