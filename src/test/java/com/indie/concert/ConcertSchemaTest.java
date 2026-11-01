package com.indie.concert;

import com.indie.concert.domain.booking.entity.BookingEntity;
import com.indie.concert.domain.booking.repository.BookingRepository;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.queue.entity.AdmittedMember;
import com.indie.concert.domain.queue.entity.QueueEntryEntity;
import com.indie.concert.domain.queue.repository.QueueEntryRepository;
import com.indie.concert.domain.queue.entity.QueueMember;
import com.indie.concert.domain.queue.repository.JdbcQueueEntryRepository;
import com.indie.concert.domain.seat.repository.SeatRepository;
import com.indie.concert.domain.stage.entity.StageDrawingEntity;
import com.indie.concert.domain.stage.repository.StageDrawingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 운영 경로 검증 — 로컬은 ddl-auto: update 라 Flyway V1 이 한 번도 돌지 않습니다.
 * 여기서 Flyway 로 스키마를 만들고 ddl-auto: validate 로 엔티티와 대조합니다 (어긋나면 컨텍스트 기동 실패).
 * 동시성 · 1인 1예매처럼 DB 가 최종 보장하는 규칙도 실제 PostgreSQL 에서 확인합니다. Docker 가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcQueueEntryRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConcertSchemaTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=concert");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired SeatRepository seatRepository;
    @Autowired ConcertRepository concertRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired QueueEntryRepository queueEntries;
    @Autowired StageDrawingRepository stageDrawings;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    /** ★ 같은 좌석을 고른 20명이 동시에 결제 승인 → 확보는 정확히 1명 (락 없이 조건부 UPDATE 만으로 — FR-CON-23 · 27) */
    @Test
    void concurrentClaimsOnOneSeatHaveOneWinner() throws Exception {
        long concertId = concert("race");
        long seatId = seat(concertId);
        OffsetDateTime now = OffsetDateTime.now();

        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Callable<Integer>> claims = new ArrayList<>();
        for (long user = 1; user <= 20; user++) {
            long userId = user;
            long bookingId = select(userId, concertId, seatId, now);   // 20명 모두 고를 수 있음 (경합)
            claims.add(() -> claim(seatId, bookingId, userId, now));
        }
        int won = 0;
        for (Future<Integer> f : pool.invokeAll(claims)) won += f.get();
        pool.shutdown();

        assertThat(won).isEqualTo(1);
    }

    /** ★ 승인 중(HELD)인 좌석은 claim 시간이 지나도 다른 사람이 가져가지 못합니다 — 결제 결과를 확인한 뒤에만 풉니다 */
    @Test
    void claimedSeatIsNotTakenUntilUnclaimed() {
        long concertId = concert("pay");
        long seatId = seat(concertId);
        OffsetDateTime now = OffsetDateTime.now();
        long first = select(1L, concertId, seatId, now);
        long second = select(2L, concertId, seatId, now);
        assertThat(claim(seatId, first, 1L, now)).isEqualTo(1);

        assertThat(claim(seatId, second, 2L, now.plusMinutes(1))).isZero();
        assertThat(seatRepository.findById(seatId).orElseThrow().getBookingId()).isEqualTo(first);

        tx.executeWithoutResult(s -> seatRepository.unclaim(first));                    // 승인 전 거부 → 다시 경합
        assertThat(claim(seatId, second, 2L, now)).isEqualTo(1);
    }

    /** 닫힌 예매(취소 · 만료)로는 확보할 수 없습니다 */
    @Test
    void claimNeedsPendingBooking() {
        long concertId = concert("closed");
        long seatId = seat(concertId);
        OffsetDateTime now = OffsetDateTime.now();
        long bookingId = select(1L, concertId, seatId, now);
        tx.executeWithoutResult(s -> bookingRepository.cancel(bookingId));

        assertThat(claim(seatId, bookingId, 1L, now)).isZero();
    }

    /** 경합 표시 · 선택 만료 — 3분 지난 선택 · claim 시간이 지난 확보만 정리 대상 */
    @Test
    void pendingSelectionsAreShownAndExpired() {
        long concertId = concert("expiry");
        long seatA = seat(concertId);
        long seatB = seat(concertId, "2");
        OffsetDateTime now = OffsetDateTime.now();
        long fresh = select(1L, concertId, seatA, now);
        long stale = select(2L, concertId, seatA, now.minusMinutes(4));
        long paying = select(3L, concertId, seatB, now.minusMinutes(4));
        claim(seatB, paying, 3L, now.minusSeconds(40));                                  // 승인 응답을 못 받은 채 claim 만료 (10초 전)

        assertThat(bookingRepository.findPendingSeatIds(concertId, now.minusMinutes(3))).containsExactly(seatA);
        assertThat(bookingRepository.findExpiredPending(now, now.minusMinutes(3), 50))
                .contains(stale, paying).doesNotContain(fresh);
    }

    /** ★ 1인 1예매의 최종 방어 — 같은 공연에 진행 중 · 확정 예매는 하나 (uq_booking_active · Flyway 에만 있음) */
    @Test
    void oneActiveBookingPerUserPerConcert() {
        long concertId = concert("once");
        OffsetDateTime now = OffsetDateTime.now();
        long first = book(5L, concertId, now);

        assertThatThrownBy(() -> book(5L, concertId, now)).isInstanceOf(DataIntegrityViolationException.class);

        tx.executeWithoutResult(s -> bookingRepository.cancel(first));
        assertThat(book(5L, concertId, now)).isPositive();
    }

    /** ★ 같은 QR 을 검표 다섯 대가 동시에 찍어도 입장 기록은 한 번 · 결제 전 예매는 입장 불가 (checked_in_at 조건부 UPDATE · V6) */
    @Test
    void checkInIsRecordedOnce() throws Exception {
        long concertId = concert("check-in");
        OffsetDateTime now = OffsetDateTime.now();
        long paid = book(1L, concertId, now);
        long pending = book(2L, concertId, now);
        tx.executeWithoutResult(s -> bookingRepository.confirm(paid));

        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Callable<Integer>> scans = new ArrayList<>();
        for (long checker = 1; checker <= 5; checker++) {
            long checkerId = checker;
            scans.add(() -> tx.execute(s -> bookingRepository.checkIn(paid, checkerId, now)));
        }
        int recorded = 0;
        for (Future<Integer> f : pool.invokeAll(scans)) recorded += f.get();
        pool.shutdown();

        assertThat(recorded).isEqualTo(1);
        assertThat(bookingRepository.findById(paid).orElseThrow().getCheckedInAt()).isNotNull();
        Integer unpaid = tx.execute(s -> bookingRepository.checkIn(pending, 1L, now));
        assertThat(unpaid).isZero();
    }

    /** 스냅샷 upsert 는 seq 가 같으면 입장 · 이탈 기록을 WAITING 으로 되돌리지 않고, 재진입(더 큰 seq)이면 되살립니다 */
    @Test
    void queueSnapshotNeverRewindsAdmission() {
        long concertId = concert("queue");
        queueEntries.upsertWaiting(concertId, List.of(new QueueMember(1, 1), new QueueMember(2, 2), new QueueMember(3, 3)));
        queueEntries.markAdmitted(concertId, List.of(new AdmittedMember(1, 1, "tok-1")), OffsetDateTime.now());
        queueEntries.upsertWaiting(concertId, List.of(new QueueMember(1, 1)));            // 늦게 온 스냅샷

        assertThat(queueEntries.findPosition(concertId, 1).orElseThrow().status()).isEqualTo(QueueEntryEntity.Status.ADMITTED);
        assertThat(queueEntries.findWaitingAfter(concertId, 0, 10)).extracting(QueueMember::userId).containsExactly(2L, 3L);
        assertThat(queueEntries.countWaitingBefore(concertId, 3)).isEqualTo(1);

        queueEntries.upsertWaiting(concertId, List.of(new QueueMember(1, 9)));            // 다시 줄 섬 → 맨 뒤
        assertThat(queueEntries.findWaitingAfter(concertId, 0, 10)).extracting(QueueMember::userId).containsExactly(2L, 3L, 1L);
        assertThat(queueEntries.maxSeq(concertId)).isEqualTo(9);
    }

    /** 입장 토큰은 DB 에도 남고(Redis 복구 원천), 이탈하면 지워집니다 · 10분이 지난 입장은 복구 대상이 아닙니다 */
    @Test
    void admittedEntryTokenIsKeptForRestore() {
        long concertId = concert("entry");
        OffsetDateTime now = OffsetDateTime.now();
        queueEntries.upsertWaiting(concertId, List.of(new QueueMember(1, 1), new QueueMember(2, 2)));
        queueEntries.markAdmitted(concertId, List.of(new AdmittedMember(1, 1, "tok-1"), new AdmittedMember(2, 2, "tok-2")), now);
        queueEntries.markLeft(concertId, 2);

        assertThat(queueEntries.findAdmittedSince(concertId, now.minusMinutes(10)))
                .extracting(QueueEntryRepository.ActiveEntry::entryToken).containsExactly("tok-1");
        assertThat(queueEntries.findAdmittedSince(concertId, now.plusSeconds(1))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT entry_token FROM queue_entry WHERE concert_id = ? AND user_id = 2",
                String.class, concertId)).isNull();
    }

    @Test
    void queueRebuildQueryIsCoveredByIndex() {
        Integer index = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'concert' AND indexname = 'idx_queue_rebuild'", Integer.class);
        assertThat(index).isEqualTo(1);
    }

    /** artist.updated — 그 아티스트 공연에만 반영 · 장르가 null 이면 기존 장르를 지우지 않습니다 */
    @Test
    void artistUpdateRenamesOnlyThatArtistsConcerts() {
        long mine = concert("artist-mine");
        long other = concert("artist-other");
        jdbc.update("UPDATE concert SET artist_id = 99 WHERE id = ?", mine);

        tx.executeWithoutResult(s -> concertRepository.updateArtist(99, "새소년 밴드", "록"));
        tx.executeWithoutResult(s -> concertRepository.updateArtist(99, "새소년밴드", null));

        assertThat(jdbc.queryForObject("SELECT artist_name || '/' || artist_genre FROM concert WHERE id = ?", String.class, mine))
                .isEqualTo("새소년밴드/록");
        assertThat(jdbc.queryForObject("SELECT artist_name FROM concert WHERE id = ?", String.class, other)).isEqualTo("새소년");
    }

    /** 무대 그리기 원본(가안)은 JSONB 로 저장되고, 같은 공연장에 다시 보내면 한 줄을 덮어씁니다 */
    @Test
    void stageDrawingIsStoredAsJsonbOnePerVenue() {
        long venueId = jdbc.queryForObject("SELECT venue_id FROM concert WHERE id = ?", Long.class, concert("stage"));
        OffsetDateTime now = OffsetDateTime.now();
        drawStage(venueId, "{\"polygon\": [[0, 0], [10, 0], [10, 5]]}", now);
        drawStage(venueId, "{\"polygon\": [[0, 0], [12, 0], [12, 8], [0, 8]], \"width_m\": 12}", now.plusMinutes(1));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM stage_drawing WHERE venue_id = ?", Integer.class, venueId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(payload -> 'polygon') FROM stage_drawing WHERE venue_id = ?",
                Integer.class, venueId)).isEqualTo(4);
    }

    /** 좌석 고르기 (N10 의 저장 부분) — 기준 시각 at */
    private long select(long userId, long concertId, long seatId, OffsetDateTime at) {
        long bookingId = book(userId, concertId, at);
        jdbc.update("INSERT INTO booking_seat (booking_id, seat_id) VALUES (?, ?)", bookingId, seatId);
        return bookingId;
    }

    /** 결제 승인 직전 확보 — claim 은 until 까지 (30초) */
    private int claim(long seatId, long bookingId, long userId, OffsetDateTime at) {
        Integer claimed = tx.execute(s -> seatRepository.claim(seatId, bookingId, userId, at.plusSeconds(30)));
        return claimed == null ? 0 : claimed;
    }

    private long book(long userId, long concertId, OffsetDateTime at) {
        Long id = tx.execute(s -> bookingRepository.save(new BookingEntity(userId, concertId, 55000, at)).getId());
        return id == null ? 0 : id;
    }

    private void drawStage(long venueId, String payload, OffsetDateTime at) {
        tx.executeWithoutResult(s -> {
            StageDrawingEntity drawing = stageDrawings.findById(venueId).orElseGet(() -> new StageDrawingEntity(venueId));
            drawing.replace(payload, at);
            stageDrawings.save(drawing);
        });
    }

    private long concert(String key) {
        Long venueId = jdbc.queryForObject("""
                INSERT INTO venue (name, region) VALUES (?, 'SEOUL')
                ON CONFLICT (name) DO UPDATE SET region = EXCLUDED.region RETURNING id
                """, Long.class, "롤링홀");
        return jdbc.queryForObject("""
                INSERT INTO concert (artist_id, artist_name, venue_id, title, type, starts_at, booking_open_at,
                                     source, external_id, updated_at)
                VALUES (7, '새소년', ?, '단독공연', 'SOLO', now() + interval '30 days', now() - interval '1 hour',
                        'test', ?, now()) RETURNING id
                """, Long.class, venueId, key);
    }

    private long seat(long concertId) {
        return seat(concertId, "1");
    }

    private long seat(long concertId, String rowNo) {
        return jdbc.queryForObject("""
                INSERT INTO seat (concert_id, section, row_no, seat_no, price, status)
                VALUES (?, 'A', ?, 1, 55000, 'AVAILABLE') RETURNING id
                """, Long.class, concertId, rowNo);
    }
}
