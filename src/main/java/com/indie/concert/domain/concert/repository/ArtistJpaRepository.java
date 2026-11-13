package com.indie.concert.domain.concert.repository;

import com.indie.concert.domain.concert.entity.ArtistEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface ArtistJpaRepository extends JpaRepository<ArtistEntity, Long>, ArtistRepository {

    @Override
    @Modifying
    @Query(value = """
            INSERT INTO artist (id, name, genre, updated_at) VALUES (:id, :name, CAST(:genre AS VARCHAR), :updatedAt)
            ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, genre = EXCLUDED.genre, updated_at = EXCLUDED.updated_at
             WHERE artist.updated_at <= EXCLUDED.updated_at
            """, nativeQuery = true)
    int upsert(@Param("id") long id, @Param("name") String name, @Param("genre") String genre,
               @Param("updatedAt") OffsetDateTime updatedAt);
}
