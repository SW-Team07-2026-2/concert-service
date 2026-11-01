package com.indie.concert.domain.concert.repository;

import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.Region;
import com.indie.concert.domain.concert.entity.VenueEntity;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.OffsetDateTime;

public interface VenueJpaRepository extends JpaRepository<VenueEntity, Long>, JpaSpecificationExecutor<VenueEntity>,
        VenueRepository {

    @Override
    default Page<VenueEntity> search(Region region, String nameContains, OffsetDateTime upcomingFrom, Pageable pageable) {
        return findAll(Specification.where(inRegion(region)).and(nameContains(nameContains)).and(hasUpcoming(upcomingFrom)),
                pageable);
    }

    private static Specification<VenueEntity> inRegion(Region region) {
        if (region == null) return null;
        return (root, query, cb) -> cb.equal(root.get("region"), region);
    }

    private static Specification<VenueEntity> nameContains(String q) {
        if (q == null || q.isBlank()) return null;
        String pattern = "%" + q.strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern, '\\');
    }

    private static Specification<VenueEntity> hasUpcoming(OffsetDateTime from) {
        if (from == null) return null;
        return (root, query, cb) -> {
            Subquery<Long> upcoming = query.subquery(Long.class);
            Root<ConcertEntity> c = upcoming.from(ConcertEntity.class);
            upcoming.select(c.get("id"))
                    .where(cb.equal(c.get("venueId"), root.get("id")), cb.greaterThanOrEqualTo(c.get("startsAt"), from));
            return cb.exists(upcoming);
        };
    }
}
