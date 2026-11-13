package com.indie.concert.domain.concert.repository;

import com.indie.concert.domain.concert.entity.ConcertArtistEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ConcertArtistJpaRepository extends JpaRepository<ConcertArtistEntity, ConcertArtistEntity.Key>,
        ConcertArtistRepository {

    @Override
    @Query(value = """
            SELECT ca.concert_id AS concertId, a.id AS artistId, a.name AS name, a.genre AS genre
            FROM concert_artist ca JOIN artist a ON a.id = ca.artist_id
            WHERE ca.concert_id IN (:concertIds)
            ORDER BY ca.concert_id, ca.billing_order
            """, nativeQuery = true)
    List<LineupRow> findLineups(@Param("concertIds") Collection<Long> concertIds);

    @Override
    @Modifying
    @Query(value = "DELETE FROM concert_artist WHERE concert_id = :concertId", nativeQuery = true)
    int deleteLineup(@Param("concertId") long concertId);

    @Override
    @Modifying
    @Query(value = """
            INSERT INTO concert_artist (concert_id, artist_id, billing_order)
            SELECT :concertId, a.artist_id, CAST(a.ord AS INTEGER)
            FROM unnest(CAST(:artistIds AS bigint[])) WITH ORDINALITY AS a(artist_id, ord)
            """, nativeQuery = true)
    int insertLineup(@Param("concertId") long concertId, @Param("artistIds") Long[] artistIds);
}
