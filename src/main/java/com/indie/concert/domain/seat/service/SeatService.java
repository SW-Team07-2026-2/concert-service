package com.indie.concert.domain.seat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.ConcertSessionEntity;
import com.indie.concert.domain.concert.exception.SessionErrorCode;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.concert.repository.ConcertSessionRepository;
import com.indie.concert.domain.layout.repository.LayoutRepository;
import com.indie.concert.domain.queue.service.QueueService;
import com.indie.concert.domain.seat.dto.request.SeatPriceRequest;
import com.indie.concert.domain.seat.dto.response.FloorView;
import com.indie.concert.domain.seat.dto.response.SeatMap;
import com.indie.concert.domain.seat.dto.response.SeatPriced;
import com.indie.concert.domain.seat.dto.response.SeatView;
import com.indie.concert.domain.seat.dto.response.SectionView;
import com.indie.concert.domain.seat.repository.ConcertSeatRepository;
import com.indie.concert.domain.seat.repository.SeatCache;
import com.indie.concert.global.common.exception.CommonErrorCode;
import com.indie.concert.global.common.exception.GeneralException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 회차별 좌석 화면과 관리자 가격 설정을 처리합니다.
 * 화면 타이머는 입장 토큰의 남은 시간을 사용하며 좌석 선점과 결제는 BookingService가 담당합니다.
 */
@Service
public class SeatService {

    private static final Logger log = LoggerFactory.getLogger(SeatService.class);
    private static final TypeReference<List<List<BigDecimal>>> SHAPE = new TypeReference<>() {};

    private final ConcertRepository concertRepository;
    private final ConcertSessionRepository sessionRepository;
    private final ConcertSeatRepository concertSeatRepository;
    private final LayoutRepository layoutRepository;
    private final SeatCache seatCache;
    private final QueueService queueService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * k6 3,000명 - 좌석 화면 요청마다 325KB 를 캐시 Redis 에서 객체로 풀고 다시 JSON 으로 만들어 concert CPU 스로틀, * GC 멈춤(최대 459ms), 메모리(96%)의 주원인이었습니다. 응답 본문(타이머 없는 JSON 바이트)을 Pod 메모리에 1초 둡니다 -
     * 비면 한 요청만 만들고 나머지는 그 결과를 기다립니다. 배치, 상태는 최대 약 3초 늦을 수 있습니다 (캐시 Redis 2초 + 1초, 선점은 서버가 다시 확인)
     */
    private final Cache<Long, SeatMapBody> bodies = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(1)).maximumSize(100).build();   // 키 = 회차 id

    /** 공연의 회차 id (시작 순) - 좌석 화면 요청마다 DB 를 보지 않게 3초 (QueueService 판매 값과 같은 간격). 없는 공연은 캐시하지 않음 */
    private final Cache<Long, List<Long>> sessionIds = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(3)).maximumSize(1_000).build();

    public SeatService(ConcertRepository concertRepository, ConcertSessionRepository sessionRepository,
                       ConcertSeatRepository concertSeatRepository, LayoutRepository layoutRepository, SeatCache seatCache,
                       QueueService queueService, ObjectMapper objectMapper, Clock clock) {
        this.concertRepository = concertRepository;
        this.sessionRepository = sessionRepository;
        this.concertSeatRepository = concertSeatRepository;
        this.layoutRepository = layoutRepository;
        this.seatCache = seatCache;
        this.queueService = queueService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 좌석 화면 - 응답 본문(ApiResponse JSON). 배치, 상태는 캐시, 타이머는 요청마다. 누가 잡았는지는 내보내지 않습니다.
     * 입장 토큰을 보내면 먼저 확인합니다 (만료, 다른 토큰이면 대기열로 돌려보냄). 안 보내면 타이머 없이 배치만 (대기열을 끈 공연)
     * sessionId - 회차가 하나면 생략 가능, 둘 이상이면 필수(400 SESSION_REQUIRED), 이 공연 회차가 아니면 404 SESSION_NOT_FOUND
     *
     * @param concertId 공연 ID
     * @param sessionId 조회할 공연 회차 ID
     * @param userId 조회하거나 처리할 회원 ID
     * @param entryToken 대기열 통과 후 발급된 입장 토큰
     */
    public byte[] seatsJson(long concertId, Long sessionId, long userId, String entryToken) {
        Long left = entryToken == null || entryToken.isBlank() ? null : queueService.entryLeft(concertId, userId, entryToken);
        long session = session(concertId, sessionId);
        SeatMapBody body = bodies.get(session, id -> SeatMapBody.of(objectMapper, map(concertId, id)));
        return left == null ? body.json() : body.withTimer(objectMapper, OffsetDateTime.now(clock).plusSeconds(left), left);
    }

    /** 배치, 상태 (타이머 없음) - 캐시 Redis 2초, 비면 DB */
    SeatMap map(long concertId, long sessionId) {
        return seatCache.get(sessionId).orElseGet(() -> load(concertId, sessionId));
    }

    private long session(long concertId, Long sessionId) {
        List<Long> ids = sessionIds.get(concertId, id -> {
            List<Long> found = sessionRepository.findByConcertIdOrderByStartsAtAsc(id).stream().map(ConcertSessionEntity::getId).toList();
            return found.isEmpty() ? null : found;
        });
        if (ids == null) throw new GeneralException(CommonErrorCode.NOT_FOUND);
        if (sessionId == null) {
            if (ids.size() > 1) throw new GeneralException(SessionErrorCode.SESSION_REQUIRED);
            return ids.get(0);
        }
        if (!ids.contains(sessionId)) throw new GeneralException(SessionErrorCode.SESSION_NOT_FOUND);
        return sessionId;
    }

    /**
     * 관리자 - 공연장 배치 좌석을 이 공연 판매 좌석으로 (좌석별 가격). 이미 있으면 가격만 바꿉니다.
     * 선점, 결제 중, 판매된 좌석은 그대로 둡니다 (skipped) - 예매 금액이 어긋나지 않게.
     * 가격은 공연 단위 - 모든 회차에 같은 가격. saved, skipped 는 좌석 * 회차로 셉니다 (회차가 하나면 전과 같음)
     *
     * @param concertId 공연 ID
     * @param seats 가격을 설정할 좌석 목록
     */
    @Transactional
    public SeatPriced price(long concertId, List<SeatPriceRequest.SeatPrice> seats) {
        ConcertEntity concert = concertRepository.findById(concertId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        if (new HashSet<>(seats.stream().map(SeatPriceRequest.SeatPrice::seatId).toList()).size() != seats.size()) {
            throw new GeneralException(CommonErrorCode.INVALID_REQUEST, "같은 좌석이 두 번 있습니다");
        }
        Long[] seatIds = seats.stream().map(SeatPriceRequest.SeatPrice::seatId).toArray(Long[]::new);
        if (concertSeatRepository.countVenueSeats(concert.getVenueId(), seatIds) != seatIds.length) {
            throw new GeneralException(CommonErrorCode.INVALID_REQUEST, "이 공연장 배치에 없는 좌석이 있습니다");
        }
        int sessions = sessionRepository.findByConcertIdOrderByStartsAtAsc(concertId).size();
        int saved = concertSeatRepository.upsertPrices(concertId, seatIds,
                seats.stream().map(SeatPriceRequest.SeatPrice::price).toArray(Integer[]::new));
        int skipped = seatIds.length * sessions - saved;
        log.info("concert_seats_priced concertId={} sessions={} saved={} skipped={}", concertId, sessions, saved, skipped);
        return new SeatPriced(saved, skipped);
    }

    /** 공연장의 층, 구역 전부 + 이 회차에서 파는 좌석 (쿼리 2번 - 구역 모양을 좌석마다 싣지 않게) */
    private SeatMap load(long concertId, long sessionId) {
        ConcertEntity concert = concertRepository.findById(concertId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        Map<Long, List<SeatView>> bySection = new HashMap<>();
        long total = 0;
        long available = 0;
        for (ConcertSeatRepository.SeatMapRow s : concertSeatRepository.findSeatMap(concertId, sessionId)) {
            bySection.computeIfAbsent(s.getSectionId(), id -> new ArrayList<>()).add(new SeatView(s.getId(), s.getRowNo(),
                    s.getSeatNo(), s.getX(), s.getY(), s.getAngle(), s.getPrice(), s.getStatus()));
            total++;
            if (!"SOLD".equals(s.getStatus())) available++;
        }
        Map<Long, String> floorNames = new LinkedHashMap<>();
        Map<Long, List<SectionView>> byFloor = new HashMap<>();
        for (LayoutRepository.SectionRow r : layoutRepository.findSections(concert.getVenueId())) {
            floorNames.putIfAbsent(r.getFloorId(), r.getFloorName());
            byFloor.computeIfAbsent(r.getFloorId(), id -> new ArrayList<>()).add(new SectionView(r.getId(), r.getName(),
                    shape(r.getShape()), r.getAngle(), bySection.getOrDefault(r.getId(), List.of())));
        }
        List<FloorView> floors = floorNames.entrySet().stream()
                .map(f -> new FloorView(f.getKey(), f.getValue(), byFloor.get(f.getKey()))).toList();
        SeatMap map = new SeatMap(concertId, sessionId, concert.getMaxSeatsPerUser(), total, available, null, null, floors);
        seatCache.put(map);
        return map;
    }

    private List<List<BigDecimal>> shape(String json) {
        try {
            return objectMapper.readValue(json, SHAPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("section.shape 가 꼭짓점 목록이 아닙니다", e);   // 저장 때 검증하므로 생기지 않습니다
        }
    }
}
