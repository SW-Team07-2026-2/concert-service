package com.indie.concert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.concert.domain.booking.entity.BookingEntity;
import com.indie.concert.domain.booking.repository.BookingRepository;
import com.indie.concert.domain.concert.dto.event.ConcertEvents;
import com.indie.concert.domain.concert.dto.request.ConcertCreateRequest;
import com.indie.concert.domain.concert.dto.request.VenueCreateRequest;
import com.indie.concert.domain.concert.dto.response.ArtistRef;
import com.indie.concert.domain.concert.dto.response.ConcertDetail;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.Region;
import com.indie.concert.domain.concert.repository.ArtistRepository;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.concert.service.ArtistService;
import com.indie.concert.domain.concert.service.ConcertAdminService;
import com.indie.concert.domain.concert.service.ConcertService;
import com.indie.concert.domain.concert.service.LineupReader;
import com.indie.concert.domain.layout.dto.request.SeatPoint;
import com.indie.concert.domain.layout.dto.request.SectionLayoutRequest;
import com.indie.concert.domain.layout.dto.request.SectionShape;
import com.indie.concert.domain.layout.service.LayoutService;
import com.indie.concert.domain.queue.entity.AdmittedMember;
import com.indie.concert.domain.queue.entity.QueueEntryEntity;
import com.indie.concert.domain.queue.repository.QueueEntryRepository;
import com.indie.concert.domain.queue.entity.QueueMember;
import com.indie.concert.domain.queue.repository.QueueEntryRepositoryAdapter;
import com.indie.concert.domain.seat.repository.ConcertSeatRepository;
import com.indie.concert.global.common.exception.GeneralException;
import com.indie.concert.global.config.ConcertProperties;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * 운영 경로 검증 — 로컬은 ddl-auto: update 라 Flyway V1 이 한 번도 돌지 않습니다.
 * 여기서 Flyway 로 스키마를 만들고 ddl-auto: validate 로 엔티티와 대조합니다 (어긋나면 컨텍스트 기동 실패).
 * 1인 1예매 · 검표처럼 DB 가 최종 보장하는 규칙도 실제 PostgreSQL 에서 확인합니다 (좌석 선점 흐름은 SeatHoldFlowTest). Docker 가 없으면 건너뜁니다.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QueueEntryRepositoryAdapter.class, OutboxAppender.class, ConcertSchemaTest.Beans.class, LayoutService.class,
        ConcertAdminService.class, ConcertService.class, LineupReader.class, ConcertEvents.class, ArtistService.class})
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
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean ConcertProperties props() {
            return new ConcertProperties(null, null, null, "Asia/Seoul", null, null, null, null, null, null, null);
        }
    }

    @Autowired ConcertSeatRepository seatRepository;
    @Autowired ConcertRepository concertRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired QueueEntryRepository queueEntries;
    @Autowired ArtistRepository artists;
    @Autowired LayoutService layoutService;
    @Autowired ConcertAdminService admin;
    @Autowired ArtistService artistService;
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
                FROM concert_seat s JOIN booking b ON b.id = s.booking_id WHERE b.id = ?
                """, Boolean.class, bookingId)).isTrue();
    }

    /** 정리 대상 — 서버 마감이 지난 선점(결제 중 제외) · 승인 응답 없이 1분이 지난 결제 중 (81A) */
    @Test
    void expiredHoldsAndStalePayingAreFound() {
        long concertId = concert("expiry");
        long open = hold(1L, concertId, seat(concertId));
        long ended = hold(2L, concertId, seat(concertId));
        long paying = hold(3L, concertId, seat(concertId));
        long stale = hold(4L, concertId, seat(concertId));
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

    /** 검표 화면 공연 목록 — 끝 시각이 있으면 그 시각까지, 없으면 시작 + 6시간까지. 끝이 시작보다 앞이면 DB 가 거부 */
    @Test
    void entryOpenConcertsUseEndsAtWhenKnown() {
        OffsetDateTime now = OffsetDateTime.now();
        long ended = concert("entry-ended");
        long festival = concert("entry-festival");
        long noEnd = concert("entry-no-end");
        long tooOld = concert("entry-too-old");
        jdbc.update("UPDATE concert SET booking_open_at = NULL WHERE id IN (?, ?, ?, ?)", ended, festival, noEnd, tooOld);   // 오픈 < 시작 (ck_concert_open_before_start)
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

    // ── 공연장 배치 (관리자 무대 그리기) ───────────────────────────

    /** 층 → 구역 → 좌석으로 저장 · 같은 구역을 다시 보내면 모양을 덮어쓰고 좌석을 새로 넣습니다 (층 · 구역 id 는 그대로) */
    @Test
    void sectionLayoutIsSavedAndReplaced() {
        long venueId = venue("배치홀");
        var first = layoutService.saveSection(venueId, layout(venueId, "1F", "A", 49.1, 6, 9));
        var again = layoutService.saveSection(venueId, layout(venueId, "1F", "A", -12.5, 2, 3));
        var other = layoutService.saveSection(venueId, layout(venueId, "1F", "B", 0, 1, 1));

        assertThat(again.floorId()).isEqualTo(first.floorId()).isEqualTo(other.floorId());
        assertThat(again.sectionId()).isEqualTo(first.sectionId()).isNotEqualTo(other.sectionId());
        assertThat(first.seatCount()).isEqualTo(54);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat WHERE section_id = ?", Integer.class, first.sectionId())).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT angle FROM section WHERE id = ?", BigDecimal.class, first.sectionId()))
                .isEqualByComparingTo("-12.5");
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(shape) FROM section WHERE id = ?", Integer.class, first.sectionId()))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT x FROM seat WHERE section_id = ? AND row_no = 2 AND seat_no = 3",
                BigDecimal.class, first.sectionId())).isEqualByComparingTo("0.23");
    }

    /** 이 구역 좌석을 파는 공연이 있으면 409 — 모양 · 좌석 모두 그대로 (롤백) */
    @Test
    void sectionInUseCannotBeRedrawn() {
        long venueId = venue("판매중홀");
        var saved = layoutService.saveSection(venueId, layout(venueId, "1F", "A", 10, 1, 2));
        long concertId = concert("in-use", venueId);
        sell(concertId, venueId);

        assertThatThrownBy(() -> layoutService.saveSection(venueId, layout(venueId, "1F", "A", 20, 3, 3)))
                .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getErrorCode().getCode()).isEqualTo("SECTION_IN_USE"));
        assertThat(jdbc.queryForObject("SELECT angle FROM section WHERE id = ?", BigDecimal.class, saved.sectionId()))
                .isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat WHERE section_id = ?", Integer.class, saved.sectionId())).isEqualTo(2);
    }

    /** 좌석 가격 — 다른 공연장 좌석은 세지 않고, 다시 보내면 빈 좌석만 가격이 바뀝니다 (선점 좌석은 그대로) */
    @Test
    void seatPricesUpsertOnlyAvailableSeats() {
        long venueId = venue("가격홀");
        long otherVenue = venue("남의홀");
        layoutService.saveSection(venueId, layout(venueId, "1F", "A", 0, 1, 2));
        layoutService.saveSection(otherVenue, layout(otherVenue, "1F", "A", 0, 1, 1));
        Long[] mine = jdbc.queryForList("""
                SELECT s.id FROM seat s JOIN section sc ON sc.id = s.section_id JOIN floor f ON f.id = sc.floor_id
                WHERE f.venue_id = ? ORDER BY s.seat_no""", Long.class, venueId).toArray(Long[]::new);
        Long theirs = jdbc.queryForObject("""
                SELECT s.id FROM seat s JOIN section sc ON sc.id = s.section_id JOIN floor f ON f.id = sc.floor_id
                WHERE f.venue_id = ?""", Long.class, otherVenue);
        long concertId = concert("price", venueId);

        assertThat(seatRepository.countVenueSeats(venueId, new Long[]{mine[0], mine[1], theirs})).isEqualTo(2);
        Integer created = tx.execute(s -> seatRepository.upsertPrices(concertId, mine, new Integer[]{55000, 66000}));
        assertThat(created).isEqualTo(2);
        long held = jdbc.queryForObject("SELECT id FROM concert_seat WHERE concert_id = ? AND seat_id = ?", Long.class, concertId, mine[0]);
        hold(1L, concertId, held);

        Integer changed = tx.execute(s -> seatRepository.upsertPrices(concertId, mine, new Integer[]{10000, 20000}));
        assertThat(changed).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT price FROM concert_seat WHERE concert_id = ? ORDER BY id", Integer.class, concertId))
                .containsExactly(55000, 20000);
    }

    /** 좌석 화면 — 마감이 지난 선점은 정리 전이라도 AVAILABLE (DB now() 기준) · 결제 중은 HELD */
    @Test
    void seatMapStatusFollowsDbClock() {
        long concertId = concert("map");
        long ended = seat(concertId);
        long paying = seat(concertId);
        long endedBooking = hold(1L, concertId, ended);
        long payingBooking = hold(2L, concertId, paying);
        jdbc.update("UPDATE concert_seat SET hold_expires_at = now() - interval '1 second' WHERE booking_id = ?", endedBooking);
        jdbc.update("UPDATE concert_seat SET status = 'PAYING', hold_expires_at = now() - interval '1 second' WHERE booking_id = ?",
                payingBooking);

        assertThat(seatRepository.findSeatMap(concertId)).extracting(ConcertSeatRepository.SeatMapRow::getId,
                ConcertSeatRepository.SeatMapRow::getStatus).containsExactly(
                tuple(ended, "AVAILABLE"), tuple(paying, "HELD"));
    }

    // ── 공연 · 라인업 (관리자 등록) ─────────────────────────────

    /** 페스티벌 등록 — 라인업 순서 그대로 · 장르는 관리자 값 · concert.created 에 artistIds */
    @Test
    void festivalIsCreatedWithLineupAndEvent() throws Exception {
        long a1 = artist(101, "새소년", "INDIE_ROCK");
        long a2 = artist(102, "실리카겔", "PSYCHEDELIC");
        long venueId = admin.createVenue(new VenueCreateRequest("페스티벌홀", Region.SEOUL, null, 5000)).id();

        ConcertDetail detail = admin.create(festival(venueId, "펜타포트", List.of(a2, a1)));

        assertThat(detail.genre()).isEqualTo("ROCK");
        assertThat(detail.artists()).extracting(ArtistRef::id).containsExactly(a2, a1);
        assertThat(detail.maxSeatsPerUser()).isEqualTo(4);
        assertThat(lastEvent("concert.created").get("artistIds").toString()).isEqualTo("[" + a2 + "," + a1 + "]");
        assertThat(concertRepository.search(new ConcertRepository.Filter(null, null, null, null, null, a1),
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent()).extracting(ConcertEntity::getId).contains(detail.id());
        assertThat(concertRepository.findForCalendar(detail.startsAt().minusDays(1), detail.startsAt().plusDays(1), List.of(a1)))
                .extracting(ConcertEntity::getId).contains(detail.id());
    }

    /** 라인업 수정 — 새로 들어온 아티스트만 concert.lineup_updated 의 addedArtistIds · 같은 목록 · 빼기만 하면 이벤트 없음 */
    @Test
    void lineupUpdateAnnouncesOnlyAddedArtists() throws Exception {
        long a1 = artist(111, "검정치마", "INDIE_ROCK");
        long a2 = artist(112, "혁오", "INDIE_ROCK");
        long a3 = artist(113, "잔나비", "INDIE_POP");
        long venueId = venue("라인업홀");
        long id = admin.create(festival(venueId, "라인업 공개", List.of(a1))).id();

        admin.replaceLineup(id, List.of(a1, a3, a2));
        var event = lastEvent("concert.lineup_updated");
        admin.replaceLineup(id, List.of(a1, a3, a2));
        admin.replaceLineup(id, List.of(a3, a2));

        assertThat(event.get("addedArtistIds").toString()).isEqualTo("[" + a3 + "," + a2 + "]");
        assertThat(event.get("artistIds").toString()).isEqualTo("[" + a1 + "," + a3 + "," + a2 + "]");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE event_type = 'concert.lineup_updated' AND payload->>'concertId' = ?",
                Integer.class, String.valueOf(id))).isEqualTo(1);
        assertThat(admin.replaceLineup(id, List.of(a3, a2)).artists()).extracting(ArtistRef::id).containsExactly(a3, a2);
    }

    /** 단독 공연 장르 = 아티스트 장르 — artist.updated 로 같이 바뀌고 페스티벌은 그대로 · 옛 이벤트는 무시 */
    @Test
    void soloGenreFollowsArtist() {
        long a = artist(121, "새소년", "INDIE_ROCK");
        long venueId = venue("단독홀");
        ConcertDetail solo = admin.create(new ConcertCreateRequest(venueId, "단독공연", ConcertEntity.Type.SOLO, null,
                OffsetDateTime.now().plusDays(30), null, 55000, null, null, 2, List.of(a), null, null));
        long fest = admin.create(festival(venueId, "같이 나오는 페스티벌", List.of(a))).id();
        assertThat(solo.genre()).isEqualTo("INDIE_ROCK");
        assertThatThrownBy(() -> admin.create(new ConcertCreateRequest(venueId, "장르 지정", ConcertEntity.Type.SOLO, "ROCK",
                OffsetDateTime.now().plusDays(30), null, null, null, null, 2, List.of(a), null, null)))
                .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getErrorCode().getCode()).isEqualTo("INVALID_REQUEST"));

        artistService.apply(a, "새소년", "PSYCHEDELIC", OffsetDateTime.now());
        artistService.apply(a, "새소년", "JAZZ", OffsetDateTime.now().minusDays(1));   // 늦게 온 옛 이벤트

        assertThat(genre(solo.id())).isEqualTo("PSYCHEDELIC");
        assertThat(genre(fest)).isEqualTo("ROCK");
    }

    /** 복제가 안 된 아티스트 · 같은 아티스트 두 번 · 단독 공연 2명은 400 */
    @Test
    void lineupMustBeKnownDistinctArtists() {
        long a = artist(131, "아도이", "INDIE_POP");
        long venueId = venue("검증홀");

        assertThatThrownBy(() -> admin.create(festival(venueId, "없는 아티스트", List.of(a, 999_999L))))
                .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getData()).containsKey("artistIds"));
        assertThatThrownBy(() -> admin.create(festival(venueId, "중복", List.of(a, a)))).isInstanceOf(GeneralException.class);
        assertThatThrownBy(() -> admin.create(new ConcertCreateRequest(venueId, "둘", ConcertEntity.Type.SOLO, null,
                OffsetDateTime.now().plusDays(30), null, null, null, null, 2, List.of(a, artist(132, "다른", null)), null, null)))
                .isInstanceOf(GeneralException.class);
    }

    /** Postgres 는 FK 를 걸어도 참조하는 쪽 인덱스를 만들지 않습니다 — 직접 만든 것 확인 */
    @Test
    void foreignKeyLookupIndexesExist() {
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes WHERE schemaname = 'concert'
                  AND indexname IN ('idx_concert_seat_seat', 'idx_booking_seat_seat', 'idx_concert_artist_artist')
                """, String.class)).hasSize(3);
    }

    /** N10 의 저장 부분 — 예매 + 서버 마감(DB now() + 9분) + 좌석 선점 */
    private long hold(long userId, long concertId, long concertSeatId) {
        Long id = tx.execute(s -> {
            long bookingId = bookingRepository.save(new BookingEntity(userId, concertId, 55000, OffsetDateTime.now())).getId();
            bookingRepository.startHold(bookingId, 540);
            jdbc.update("INSERT INTO booking_seat (booking_id, concert_seat_id) VALUES (?, ?)", bookingId, concertSeatId);
            seatRepository.hold(concertSeatId, concertId, bookingId, userId);
            return bookingId;
        });
        return id == null ? 0 : id;
    }

    private long book(long userId, long concertId, OffsetDateTime at) {
        Long id = tx.execute(s -> bookingRepository.save(new BookingEntity(userId, concertId, 55000, at)).getId());
        return id == null ? 0 : id;
    }

    private long venue(String name) {
        return jdbc.queryForObject("""
                INSERT INTO venue (name, region) VALUES (?, 'SEOUL')
                ON CONFLICT (name) DO UPDATE SET region = EXCLUDED.region RETURNING id
                """, Long.class, name);
    }

    private long concert(String title) {
        return concert(title, venue("롤링홀"));
    }

    private long concert(String title, long venueId) {
        return jdbc.queryForObject("""
                INSERT INTO concert (venue_id, title, type, starts_at, booking_open_at, max_seats_per_user, updated_at)
                VALUES (?, ?, 'SOLO', now() + interval '30 days', now() - interval '1 hour', 4, now()) RETURNING id
                """, Long.class, venueId, title);
    }

    /** 공연장 배치에 좌석을 하나 만들고 이 공연에서 팝니다 — 반환은 concert_seat.id */
    private long seat(long concertId) {
        long venueId = jdbc.queryForObject("SELECT venue_id FROM concert WHERE id = ?", Long.class, concertId);
        long sectionId = layoutService.saveSection(venueId, layout(venueId, "1F", "S" + SECTION.incrementAndGet(), 0, 1, 1)).sectionId();
        return jdbc.queryForObject("""
                INSERT INTO concert_seat (concert_id, seat_id, price, status)
                SELECT ?, id, 55000, 'AVAILABLE' FROM seat WHERE section_id = ? RETURNING id
                """, Long.class, concertId, sectionId);
    }

    /** 공연장 좌석 전부를 이 공연에서 팝니다 */
    private void sell(long concertId, long venueId) {
        jdbc.update("""
                INSERT INTO concert_seat (concert_id, seat_id, price, status)
                SELECT ?, s.id, 55000, 'AVAILABLE' FROM seat s JOIN section sc ON sc.id = s.section_id
                JOIN floor f ON f.id = sc.floor_id WHERE f.venue_id = ?
                """, concertId, venueId);
    }

    private static final AtomicInteger SECTION = new AtomicInteger();

    /** rows × perRow 좌석 — x = 열/10 + 번호/100 (2열 3번 = 0.23) */
    private static SectionLayoutRequest layout(long venueId, String floor, String section, double angle, int rows, int perRow) {
        List<SeatPoint> seats = new ArrayList<>();
        for (int r = 1; r <= rows; r++) {
            for (int n = 1; n <= perRow; n++) {
                seats.add(new SeatPoint(floor + "-" + section + "-" + r + "-" + n, r, n,
                        BigDecimal.valueOf(r / 10.0 + n / 100.0), new BigDecimal("0.5"), BigDecimal.valueOf(angle)));
            }
        }
        List<List<BigDecimal>> shape = List.of(List.of(new BigDecimal("0.053"), new BigDecimal("0.431")),
                List.of(new BigDecimal("0.191"), new BigDecimal("0.635")), List.of(new BigDecimal("0.302"), new BigDecimal("0.505")),
                List.of(new BigDecimal("0.168"), new BigDecimal("0.299")));
        return new SectionLayoutRequest(venueId, null, floor,
                new SectionShape(section, shape, BigDecimal.valueOf(angle), rows, rows * perRow), seats);
    }

    private long artist(long id, String name, String genre) {
        tx.executeWithoutResult(s -> artists.upsert(id, name, genre, OffsetDateTime.now().minusHours(1)));
        return id;
    }

    private static ConcertCreateRequest festival(long venueId, String title, List<Long> artistIds) {
        OffsetDateTime starts = OffsetDateTime.now().plusDays(40);
        return new ConcertCreateRequest(venueId, title, ConcertEntity.Type.FESTIVAL, "ROCK", starts, starts.plusHours(8),
                99000, starts.minusDays(20), true, 4, artistIds, null, null);
    }

    private String genre(long concertId) {
        return jdbc.queryForObject("SELECT genre FROM concert WHERE id = ?", String.class, concertId);
    }

    private com.fasterxml.jackson.databind.JsonNode lastEvent(String type) throws Exception {
        return new ObjectMapper().readTree(jdbc.queryForObject(
                "SELECT payload::text FROM outbox WHERE event_type = ? ORDER BY id DESC LIMIT 1", String.class, type));
    }
}
