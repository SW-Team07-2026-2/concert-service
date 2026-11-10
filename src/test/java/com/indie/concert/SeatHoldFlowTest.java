package com.indie.concert;

import com.indie.concert.domain.booking.client.PaymentClient;
import com.indie.concert.domain.booking.dto.event.BookingEvents;
import com.indie.concert.domain.booking.dto.response.BookingCreated;
import com.indie.concert.domain.booking.repository.BookingRepository;
import com.indie.concert.domain.booking.repository.BookingSeatRepository;
import com.indie.concert.domain.booking.service.BookingService;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.concert.repository.VenueRepository;
import com.indie.concert.domain.queue.repository.QueueEntryRepository;
import com.indie.concert.domain.queue.repository.QueueEntryRepositoryAdapter;
import com.indie.concert.domain.queue.repository.QueueStore;
import com.indie.concert.domain.queue.repository.RedisQueueStore;
import com.indie.concert.domain.queue.service.QueueService;
import com.indie.concert.domain.seat.repository.SeatRepository;
import com.indie.concert.global.common.exception.GeneralException;
import com.indie.concert.global.config.ConcertProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 좌석 선점 (11/04 77A) 완료 기준 — 실제 PostgreSQL(Flyway · DB now()) + 실제 Redis(입장 토큰 TTL) 위에서 BookingService 를 그대로 돌립니다.
 * Payment 만 가짜입니다 (승인 호출 횟수 = 토스 호출 횟수 — Payment 는 승인 요청을 받아야만 토스를 부릅니다). Docker 가 없으면 건너뜁니다.
 * ③ [A,B] vs [B,C] 는 1인 1석이라 해당 없음 (79A).
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QueueEntryRepositoryAdapter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SeatHoldFlowTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=concert");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final ConcertProperties PROPS = new ConcertProperties(null, null, null, "Asia/Seoul",
            new ConcertProperties.Booking(7, 179, 60, 24, 30000),
            new ConcertProperties.Queue(20, 1000, 200, 5000, 1000, 15, 7, 2000, 3000, 5000),
            null, null, null, null, null);

    private static LettuceConnectionFactory redisFactory;
    private static QueueStore queueStore;

    @BeforeAll
    static void connectRedis() {
        redisFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        redisFactory.afterPropertiesSet();
        redisFactory.start();
        queueStore = new RedisQueueStore(new StringRedisTemplate(redisFactory), PROPS);
    }

    @AfterAll
    static void closeRedis() {
        redisFactory.destroy();
    }

    @Autowired BookingRepository bookings;
    @Autowired BookingSeatRepository bookingSeats;
    @Autowired SeatRepository seats;
    @Autowired ConcertRepository concerts;
    @Autowired VenueRepository venues;
    @Autowired QueueEntryRepository queueEntries;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private final FakePayment payment = new FakePayment();
    private BookingService service;

    @BeforeEach
    void wire() {
        QueueService queue = new QueueService(queueStore, queueEntries, concerts, PROPS, new SimpleMeterRegistry(), Clock.systemUTC());
        service = new BookingService(bookings, bookingSeats, seats, concerts, venues, queue, payment,
                mock(BookingEvents.class), tx, PROPS, Clock.systemUTC());
    }

    /** ① 100명이 같은 좌석을 동시에 → 선점 1명 · 99명 409 SEAT_TAKEN (락 없이 조건부 UPDATE 만으로) */
    @Test
    void hundredUsersOnOneSeatHaveOneHold() throws Exception {
        long concertId = concert("flow-race");
        long seatId = seat(concertId, "1");
        List<Callable<String>> tries = new ArrayList<>();
        for (long user = 1; user <= 100; user++) {
            long userId = user;
            String token = admit(concertId, userId, 60_000);
            tries.add(() -> tryHold(concertId, userId, token, seatId));
        }

        Map<String, Long> outcomes = together(50, tries).stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        assertThat(outcomes).containsOnly(Map.entry("HELD", 1L), Map.entry("SEAT_TAKEN", 99L));
        assertThat(seatStatus(seatId)).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking WHERE concert_id = ?", Long.class, concertId))
                .isEqualTo(1);   // 진 99명의 예매는 롤백 — 남지 않음
    }

    /** ② 같은 사람이 같은 좌석을 동시에 두 번 → 선점 하나 · 두 응답의 holdId(bookingId) · 주문번호 · 마감이 같음 */
    @Test
    void sameUserSameSeatTwiceGetsOneHold() throws Exception {
        long concertId = concert("flow-twice");
        for (long user = 11; user <= 15; user++) {
            long userId = user;
            long seatId = seat(concertId, "r" + user);
            String token = admit(concertId, userId, 60_000);
            Callable<BookingCreated> pick = () -> service.create(concertId, userId, token, seatId);

            List<BookingCreated> both = together(2, List.of(pick, pick));

            assertThat(both.get(1).bookingId()).isEqualTo(both.get(0).bookingId());
            assertThat(both.get(1).pgOrderId()).isNotNull().isEqualTo(both.get(0).pgOrderId());
            assertThat(both.get(1).expiresAt()).isAtSameInstantAs(both.get(0).expiresAt());   // 타이머 리셋 없음
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking WHERE concert_id = ?", Long.class, concertId))
                .isEqualTo(5);
    }

    /** ④ 마감이 지난 HELD 는 정리(30초)를 기다리지 않고 바로 다음 사람이 선점 — 늦게 돈 정리가 돌아도 새 선점은 그대로 */
    @Test
    void expiredHoldIsTakenBeforeCleanup() {
        long concertId = concert("flow-expired");
        long seatId = seat(concertId, "1");
        BookingCreated first = service.create(concertId, 21L, admit(concertId, 21L, 60_000), seatId);
        deadline(first.bookingId(), "-1 second");

        BookingCreated next = service.create(concertId, 22L, admit(concertId, 22L, 60_000), seatId);

        assertThat(seatBooking(seatId)).isEqualTo(next.bookingId());
        assertThatThrownBy(() -> service.pay(21L, first.bookingId(), "pk-21", 55000, null))
                .isInstanceOfSatisfying(GeneralException.class, e -> assertThat(e.getErrorCode().getCode()).isEqualTo("HOLD_EXPIRED"));
        assertThat(payment.confirms).hasValue(0);

        service.expirePending(50);

        assertThat(bookingStatus(first.bookingId())).isEqualTo("EXPIRED");
        assertThat(seatStatus(seatId)).isEqualTo("HELD");
        assertThat(seatBooking(seatId)).isEqualTo(next.bookingId());
    }

    /** ⑤ 마감 1초 전 결제 → PAYING — 승인 중에 마감이 지나도 남이 못 가져가고 정리도 건드리지 않음 → SOLD */
    @Test
    void payingSeatSurvivesDeadline() {
        long concertId = concert("flow-paying");
        long seatId = seat(concertId, "1");
        BookingCreated held = service.create(concertId, 31L, admit(concertId, 31L, 60_000), seatId);
        String otherToken = admit(concertId, 32L, 60_000);
        deadline(held.bookingId(), "1 second");
        AtomicReference<String> during = new AtomicReference<>();
        AtomicReference<String> other = new AtomicReference<>();
        payment.onConfirm = id -> {
            during.set(seatStatus(seatId));
            deadline(id, "-1 second");                                   // 토스 승인을 기다리는 동안 서버 마감이 지남
            other.set(tryHold(concertId, 32L, otherToken, seatId));
            service.expirePending(50);
            return new PaymentClient.Confirmed("APPROVED", null);
        };

        assertThat(service.pay(31L, held.bookingId(), "pk-31", 55000, null).status()).isEqualTo("CONFIRMED");

        assertThat(during.get()).isEqualTo("PAYING");
        assertThat(other.get()).isEqualTo("SEAT_TAKEN");
        assertThat(payment.cancelled).doesNotContain(held.bookingId());
        assertThat(seatStatus(seatId)).isEqualTo("SOLD");
        assertThat(bookingStatus(held.bookingId())).isEqualTo("CONFIRMED");
    }

    /** ⑥ 마감 1초 뒤 결제 → 410 HOLD_EXPIRED · 승인(토스) 호출 0회 */
    @Test
    void payAfterDeadlineNeverReachesToss() {
        long concertId = concert("flow-late");
        long seatId = seat(concertId, "1");
        BookingCreated held = service.create(concertId, 35L, admit(concertId, 35L, 60_000), seatId);
        deadline(held.bookingId(), "-1 second");

        assertThatThrownBy(() -> service.pay(35L, held.bookingId(), "pk-35", 55000, null))
                .isInstanceOfSatisfying(GeneralException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo("HOLD_EXPIRED");
                    assertThat(e.getErrorCode().getStatus()).isEqualTo(HttpStatus.GONE);
                });
        assertThat(payment.confirms).hasValue(0);
        assertThat(seatStatus(seatId)).isEqualTo("HELD");   // PAYING 으로 바뀌지 않음 — 다음 사람이 바로 선점 가능
        assertThat(paying(held.bookingId())).isFalse();      // 먼저 찍은 paying_at 도 롤백 — 남으면 정리가 이 선점을 못 찾음
    }

    /** ⑥-2 같은 예매의 결제 승인을 동시에 두 번 → 승인 1번 · 뒤 요청은 409 SEAT_LOCKED (410 이 아님 — 11/10) */
    @Test
    void doublePayIsSeatLockedNotExpired() throws Exception {
        long concertId = concert("flow-double");
        long seatId = seat(concertId, "1");
        BookingCreated held = service.create(concertId, 36L, admit(concertId, 36L, 60_000), seatId);
        payment.onConfirm = id -> {
            try {
                Thread.sleep(500);                                       // 토스 승인을 기다리는 동안 두 번째 요청이 도착
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return new PaymentClient.Confirmed("APPROVED", null);
        };

        List<String> results = together(2, List.<Callable<String>>of(
                () -> tryPay(36L, held.bookingId()), () -> tryPay(36L, held.bookingId())));

        assertThat(results).containsExactlyInAnyOrder("CONFIRMED", "SEAT_LOCKED");
        assertThat(payment.confirms).hasValue(1);
        assertThat(seatStatus(seatId)).isEqualTo("SOLD");
    }

    /** ⑦ 승인 응답 없음(타임아웃) → PAYING 유지 · 503 → 1분 뒤 정리가 Payment 결과대로 SOLD 또는 AVAILABLE */
    @Test
    void unknownPaymentIsSettledByCleanup() {
        long concertId = concert("flow-timeout");
        long seatPaid = seat(concertId, "1");
        long seatLost = seat(concertId, "2");
        BookingCreated paid = service.create(concertId, 41L, admit(concertId, 41L, 60_000), seatPaid);
        BookingCreated lost = service.create(concertId, 42L, admit(concertId, 42L, 60_000), seatLost);
        payment.onConfirm = id -> {
            throw new PaymentClient.Unavailable("confirm", new SocketTimeoutException("Read timed out"));
        };

        for (BookingCreated b : List.of(paid, lost)) {
            long userId = b == paid ? 41L : 42L;
            assertThatThrownBy(() -> service.pay(userId, b.bookingId(), "pk-" + userId, 55000, null))
                    .isInstanceOfSatisfying(GeneralException.class, e -> {
                        assertThat(e.getErrorCode().getCode()).isEqualTo("PAYMENT_PENDING");
                        assertThat(e.getErrorCode().getStatus().value()).isEqualTo(503);
                    });
            deadline(b.bookingId(), "-1 second");
        }
        assertThat(seatStatus(seatPaid)).isEqualTo("PAYING");
        assertThat(tryHold(concertId, 43L, admit(concertId, 43L, 60_000), seatPaid)).isEqualTo("SEAT_TAKEN");

        service.expirePending(50);                                                         // 1분 전 — 그대로
        assertThat(payment.cancelled).doesNotContain(paid.bookingId(), lost.bookingId());
        assertThat(seatStatus(seatPaid)).isEqualTo("PAYING");

        jdbc.update("UPDATE booking SET paying_at = now() - interval '61 seconds' WHERE id IN (?, ?)", paid.bookingId(), lost.bookingId());
        payment.onCancel = id -> id == paid.bookingId() ? "APPROVED" : "CANCELLED";       // Payment 가 토스에 조회한 결과 (84A)
        service.expirePending(50);

        assertThat(bookingStatus(paid.bookingId())).isEqualTo("CONFIRMED");
        assertThat(seatStatus(seatPaid)).isEqualTo("SOLD");
        assertThat(bookingStatus(lost.bookingId())).isEqualTo("EXPIRED");
        assertThat(seatStatus(seatLost)).isEqualTo("AVAILABLE");
    }

    /** ⑧ 입장 토큰 만료 1초 전 선점 → 성공 · 화면 시계는 이어서 곧 끝나고, 토큰이 끝나도 유예(약 3분) 안이면 결제 가능 (N16 은 토큰을 보지 않음) */
    @Test
    void holdJustBeforeTokenExpiryCanStillPay() throws Exception {
        long concertId = concert("flow-token-edge");
        long seatId = seat(concertId, "1");
        String token = admit(concertId, 51L, 2_000);
        Thread.sleep(1_000);                                                               // 토큰이 1초 남음

        BookingCreated held = service.create(concertId, 51L, token, seatId);
        assertThat(held.expiresIn()).isBetween(0L, 2L);
        assertThat(jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM hold_expires_at - now()) FROM booking WHERE id = ?",
                Double.class, held.bookingId())).isBetween(175.0, 181.0);
        awaitTokenGone(concertId, 51L);

        assertThat(service.pay(51L, held.bookingId(), "pk-51", 55000, null).status()).isEqualTo("CONFIRMED");
        assertThat(seatStatus(seatId)).isEqualTo("SOLD");
    }

    /** ⑨ 입장 토큰 만료 뒤 선점 → 403 QUEUE_NOT_ADMITTED · 좌석 · 예매 그대로 */
    @Test
    void holdAfterTokenExpiryIsForbidden() throws Exception {
        long concertId = concert("flow-token-gone");
        long seatId = seat(concertId, "1");
        String token = admit(concertId, 61L, 200);
        awaitTokenGone(concertId, 61L);

        assertThatThrownBy(() -> service.create(concertId, 61L, token, seatId))
                .isInstanceOfSatisfying(GeneralException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo("QUEUE_NOT_ADMITTED");
                    assertThat(e.getErrorCode().getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });
        assertThat(seatStatus(seatId)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking WHERE concert_id = ?", Long.class, concertId)).isZero();
    }

    /** 승인 호출 횟수 · 정리가 물어본 예매를 셉니다. prepare 는 Payment 처럼 같은 예매면 같은 주문번호 */
    static class FakePayment implements PaymentClient {
        final AtomicInteger confirms = new AtomicInteger();
        final Set<Long> cancelled = ConcurrentHashMap.newKeySet();
        volatile Function<Long, Confirmed> onConfirm = id -> new Confirmed("APPROVED", null);
        volatile Function<Long, String> onCancel = id -> "CANCELLED";

        @Override
        public Prepared prepare(long bookingId, long userId, int amount) {
            return new Prepared("TICKET-" + bookingId, "PENDING", null);
        }

        @Override
        public Confirmed confirm(long bookingId, long userId, String paymentKey, int amount, String forceDecline) {
            confirms.incrementAndGet();
            return onConfirm.apply(bookingId);
        }

        @Override
        public String cancel(long bookingId, String reason, boolean refundApproved) {
            cancelled.add(bookingId);
            return onCancel.apply(bookingId);
        }
    }

    private String tryHold(long concertId, long userId, String token, long seatId) {
        try {
            service.create(concertId, userId, token, seatId);
            return "HELD";
        } catch (GeneralException e) {
            return e.getErrorCode().getCode();
        }
    }

    private String tryPay(long userId, long bookingId) {
        try {
            return service.pay(userId, bookingId, "pk-" + userId, 55000, null).status();
        } catch (GeneralException e) {
            return e.getErrorCode().getCode();
        }
    }

    /** 동시에 출발 */
    private static <T> List<T> together(int threads, List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) results.add(f.get(60, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 대기열 입장 — 실제 Redis 에 입장 토큰 (ttlMs 뒤 만료) */
    private String admit(long concertId, long userId, long ttlMs) {
        String token = UUID.randomUUID().toString();
        queueStore.restoreEntries(concertId, List.of(new QueueStore.EntryGrant(userId, token, ttlMs)));
        return token;
    }

    private void awaitTokenGone(long concertId, long userId) throws InterruptedException {
        for (int i = 0; i < 50 && queueStore.entry(concertId, userId).isPresent(); i++) Thread.sleep(100);
        assertThat(queueStore.entry(concertId, userId)).isEmpty();
    }

    /** 선점 서버 마감을 DB now() 기준으로 옮김 (예매 · 좌석 둘 다) — 예: "-1 second" = 1초 전에 지남 */
    private void deadline(long bookingId, String fromNow) {
        jdbc.update("UPDATE booking SET hold_expires_at = now() + ?::interval WHERE id = ?", fromNow, bookingId);
        jdbc.update("UPDATE seat SET hold_expires_at = now() + ?::interval WHERE booking_id = ?", fromNow, bookingId);
    }

    private String seatStatus(long seatId) {
        return jdbc.queryForObject("SELECT status FROM seat WHERE id = ?", String.class, seatId);
    }

    private Long seatBooking(long seatId) {
        return jdbc.queryForObject("SELECT booking_id FROM seat WHERE id = ?", Long.class, seatId);
    }

    private boolean paying(long bookingId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT paying_at IS NOT NULL FROM booking WHERE id = ?", Boolean.class, bookingId));
    }

    private String bookingStatus(long bookingId) {
        return jdbc.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, bookingId);
    }

    /** 대기열을 켠 · 판매 중인 공연 */
    private long concert(String key) {
        Long venueId = jdbc.queryForObject("""
                INSERT INTO venue (name, region) VALUES (?, 'SEOUL')
                ON CONFLICT (name) DO UPDATE SET region = EXCLUDED.region RETURNING id
                """, Long.class, "롤링홀");
        return jdbc.queryForObject("""
                INSERT INTO concert (artist_id, artist_name, venue_id, title, type, starts_at, booking_open_at,
                                     queue_enabled, source, external_id, updated_at)
                VALUES (7, '새소년', ?, '단독공연', 'SOLO', now() + interval '30 days', now() - interval '1 hour',
                        TRUE, 'test', ?, now()) RETURNING id
                """, Long.class, venueId, key);
    }

    private long seat(long concertId, String rowNo) {
        return jdbc.queryForObject("""
                INSERT INTO seat (concert_id, section, row_no, seat_no, price, status)
                VALUES (?, 'A', ?, 1, 55000, 'AVAILABLE') RETURNING id
                """, Long.class, concertId, rowNo);
    }
}
