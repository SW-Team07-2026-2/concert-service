package com.indie.concert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.concert.domain.booking.entity.BookingEntity;
import com.indie.concert.domain.booking.repository.BookingRepository;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.queue.entity.AdmittedMember;
import com.indie.concert.domain.queue.entity.QueueEntryEntity;
import com.indie.concert.domain.queue.repository.QueueEntryRepository;
import com.indie.concert.domain.queue.entity.QueueMember;
import com.indie.concert.domain.queue.repository.QueueEntryRepositoryAdapter;
import com.indie.concert.domain.seat.repository.SeatRepository;
import com.indie.concert.domain.stage.entity.StageDrawingEntity;
import com.indie.concert.domain.stage.repository.StageDrawingRepository;
import com.indie.concert.global.outbox.OutboxAppender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 운영 경로 검증 — 로컬은 ddl-auto: update 라 Flyway V1 이 한 번도 돌지 않습니다.
 * 여기서 Flyway 로 스키마를 만들고 ddl-auto: validate 로 엔티티와 대조합니다 (어긋나면 컨텍스트 기동 실패).
 * 1인 1예매 · 검표처럼 DB 가 최종 보장하는 규칙도 실제 PostgreSQL 에서 확인합니다 (좌석 선점 흐름은 SeatHoldFlowTest). Docker 가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QueueEntryRepositoryAdapter.class, OutboxAppender.class, ConcertSchemaTest.Beans.class})
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

    @TestConfiguration
    static class Beans {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    @Autowired SeatRepository seatRepository;
    @Autowired ConcertRepository concertRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired QueueEntryRepository queueEntries;
    @Autowired StageDrawingRepository stageDrawings;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired OutboxAppender outbox;

    /** 선점 저장 (V8) — 좌석의 마감은 예매의 서버 마감(DB now() + 9분)을 그대로 씁니다 */
    @Test
    void holdCopiesServerDeadlineToSeat() {
        long concertId = concert("hold");
        long seatId = seat(concertId);
        long bookingId = hold(1L, concertId, seatId);

        assertThat(jdbc.queryForObject("""
                SELECT s.status = 'HELD' AND s.held_by = 1 AND s.hold_expires_at = b.hold_expires_at
                       AND b.hold_expires_at BETWEEN now() + interval '539 seconds' AND now() + interval '540 seconds'
                FROM seat s JOIN booking b ON b.id = s.booking_id WHERE b.id = ?
                """, Boolean.class, bookingId)).isTrue();
    }

    /** 정리 대상 — 서버 마감이 지난 선점(결제 중 제외) · 승인 응답 없이 1분이 지난 결제 중 (81A) */
    @Test
    void expiredHoldsAndStalePayingAreFound() {
        long concertId = concert("expiry");
        long open = hold(1L, concertId, seat(concertId, "1"));
        long ended = hold(2L, concertId, seat(concertId, "2"));
        long paying = hold(3L, concertId, seat(concertId, "3"));
        long stale = hold(4L, concertId, seat(concertId, "4"));
        jdbc.update("UPDATE booking SET hold_expires_at = now() - interval '1 second' WHERE id IN (?, ?, ?)", ended, paying, stale);
        tx.executeWithoutResult(s -> {
            bookingRepository.markPaying(paying);
            bookingRepository.markPaying(stale);
        });
        jdbc.update("UPDATE booking SET paying_at = now() - interval '61 seconds' WHERE id = ?", stale);

        assertThat(bookingRepository.findExpiredHolds(50)).contains(ended).doesNotContain(open, paying, stale);
        assertThat(bookingRepository.findStalePaying(60, 50)).contains(stale).doesNotContain(open, ended, paying);
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

    /** 업무 트랜잭션 안에서 jsonb 로 저장 */
    @Test
    void outboxStoresJsonbPayloadInTransaction() {
        tx.executeWithoutResult(s -> outbox.publish("concert.test", Map.of("concertId", 7)));

        assertThat(jdbc.queryForObject("SELECT payload->>'concertId' FROM outbox WHERE event_type = 'concert.test'",
                String.class)).isEqualTo("7");
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

    /** 검표 화면 공연 목록 — 끝 시각이 있으면 그 시각까지, 없으면 시작 + 6시간까지. 끝이 시작보다 앞이면 DB 가 거부 */
    @Test
    void entryOpenConcertsUseEndsAtWhenKnown() {
        OffsetDateTime now = OffsetDateTime.now();
        long ended = concert("entry-ended");
        long festival = concert("entry-festival");
        long noEnd = concert("entry-no-end");
        long tooOld = concert("entry-too-old");
        jdbc.update("UPDATE concert SET starts_at = now() - interval '2 hours', ends_at = now() - interval '1 minute' WHERE id = ?", ended);
        jdbc.update("UPDATE concert SET starts_at = now() - interval '7 hours', ends_at = now() + interval '1 hour' WHERE id = ?", festival);
        jdbc.update("UPDATE concert SET starts_at = now() - interval '5 hours' WHERE id = ?", noEnd);
        jdbc.update("UPDATE concert SET starts_at = now() - interval '7 hours' WHERE id = ?", tooOld);

        List<Long> open = concertRepository.findEntryOpen(now, now.plusHours(3), now.minusHours(6)).stream()
                .map(c -> c.getId()).toList();

        assertThat(open).contains(festival, noEnd).doesNotContain(ended, tooOld);
        assertThatThrownBy(() -> jdbc.update("UPDATE concert SET ends_at = starts_at WHERE id = ?", noEnd))
                .isInstanceOf(DataIntegrityViolationException.class);
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

    /** N10 의 저장 부분 — 예매 + 서버 마감(DB now() + 9분) + 좌석 선점 */
    private long hold(long userId, long concertId, long seatId) {
        Long id = tx.execute(s -> {
            long bookingId = bookingRepository.save(new BookingEntity(userId, concertId, 55000, OffsetDateTime.now())).getId();
            bookingRepository.startHold(bookingId, 540);
            jdbc.update("INSERT INTO booking_seat (booking_id, seat_id) VALUES (?, ?)", bookingId, seatId);
            seatRepository.hold(seatId, concertId, bookingId, userId);
            return bookingId;
        });
        return id == null ? 0 : id;
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
