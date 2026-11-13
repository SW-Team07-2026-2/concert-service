package com.indie.concert.domain.layout.repository;

import com.indie.concert.domain.layout.entity.SectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface LayoutJpaRepository extends JpaRepository<SectionEntity, Long>, LayoutRepository {

    /** DO UPDATE 는 같은 값으로 덮어 RETURNING 이 있던 행의 id 도 돌려주게 합니다 (DO NOTHING 이면 빈 결과) */
    @Override
    @Query(value = """
            INSERT INTO floor (venue_id, name) VALUES (:venueId, :name)
            ON CONFLICT (venue_id, name) DO UPDATE SET name = EXCLUDED.name
            RETURNING id
            """, nativeQuery = true)
    long upsertFloor(@Param("venueId") long venueId, @Param("name") String name);

    @Override
    @Query(value = """
            INSERT INTO section (floor_id, name, shape, angle) VALUES (:floorId, :name, CAST(:shape AS jsonb), :angle)
            ON CONFLICT (floor_id, name) DO UPDATE SET shape = EXCLUDED.shape, angle = EXCLUDED.angle
            RETURNING id
            """, nativeQuery = true)
    long upsertSection(@Param("floorId") long floorId, @Param("name") String name, @Param("shape") String shapeJson,
                       @Param("angle") BigDecimal angle);

    /** idx_concert_seat_seat 로 찾습니다 */
    @Override
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM seat s JOIN concert_seat cs ON cs.seat_id = s.id WHERE s.section_id = :sectionId)
            """, nativeQuery = true)
    boolean sectionInUse(@Param("sectionId") long sectionId);

    @Override
    @Modifying
    @Query(value = "DELETE FROM seat WHERE section_id = :sectionId", nativeQuery = true)
    int deleteSeats(@Param("sectionId") long sectionId);

    /** 한 문장 — 구역 좌석 수천 개도 왕복 1번 (IDENTITY 라 Hibernate 배치 INSERT 가 꺼져 있습니다) */
    @Override
    @Modifying
    @Query(value = """
            INSERT INTO seat (section_id, row_no, seat_no, x, y, angle)
            SELECT :sectionId, r.row_no, r.seat_no, r.x, r.y, r.angle
            FROM unnest(CAST(:rows AS int[]), CAST(:numbers AS int[]), CAST(:xs AS numeric[]), CAST(:ys AS numeric[]),
                        CAST(:angles AS numeric[])) AS r(row_no, seat_no, x, y, angle)
            """, nativeQuery = true)
    int insertSeats(@Param("sectionId") long sectionId, @Param("rows") Integer[] rows, @Param("numbers") Integer[] numbers,
                    @Param("xs") BigDecimal[] xs, @Param("ys") BigDecimal[] ys, @Param("angles") BigDecimal[] angles);

    @Override
    @Query(value = """
            SELECT f.id AS floorId, f.name AS floorName, sc.id AS id, sc.name AS name, CAST(sc.shape AS text) AS shape,
                   sc.angle AS angle
            FROM section sc JOIN floor f ON f.id = sc.floor_id
            WHERE f.venue_id = :venueId
            ORDER BY f.name, sc.name
            """, nativeQuery = true)
    List<SectionRow> findSections(@Param("venueId") long venueId);

    @Override
    @Query(value = """
            SELECT s.id AS id, s.section_id AS sectionId, s.row_no AS rowNo, s.seat_no AS seatNo, s.x AS x, s.y AS y, s.angle AS angle
            FROM seat s JOIN section sc ON sc.id = s.section_id JOIN floor f ON f.id = sc.floor_id
            WHERE f.venue_id = :venueId
            ORDER BY s.section_id, s.row_no, s.seat_no
            """, nativeQuery = true)
    List<SeatRow> findSeats(@Param("venueId") long venueId);
}
