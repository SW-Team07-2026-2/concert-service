package com.indie.concert.domain.seat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.repository.ConcertRepository;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 좌석 — 좌석 화면(배치 · 상태 · 타이머) · 관리자 좌석 가격. 좌석을 고르고 결제하는 흐름은 BookingService 에 있습니다.
 * <pre>
 * 화면 상태 (11/04 77A 선점)
 *   AVAILABLE  선택 가능  아무도 잡지 않음 · 선점 마감(DB now())이 지남 — 정리 전이라도 바로 고를 수 있음
 *   HELD       선택 중    누군가 선점(마감 전) · 결제 승인 중(PAYING) — 회색 · 못 고름 (마감 뒤 다시 풀릴 수 있음)
 *   SOLD       판매       결제 완료
 * </pre>
 * 타이머 — 화면 시계는 대기열 통과(입장 토큰 발급)부터 7분 하나입니다. 좌석 화면은 입장 토큰의 남은 시간을 그대로 돌려주고,
 * 좌석을 고르면 선점 마감이 같은 시간을 이어받습니다 (서버는 그 뒤 유예 179초를 더 지킴 — BookingService).
 */
@Service
public class SeatService {

    private static final Logger log = LoggerFactory.getLogger(SeatService.class);
    private static final TypeReference<List<List<BigDecimal>>> SHAPE = new TypeReference<>() {};

    private final ConcertRepository concertRepository;
    private final ConcertSeatRepository concertSeatRepository;
    private final LayoutRepository layoutRepository;
    private final SeatCache seatCache;
    private final QueueService queueService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SeatService(ConcertRepository concertRepository, ConcertSeatRepository concertSeatRepository,
                       LayoutRepository layoutRepository, SeatCache seatCache, QueueService queueService,
                       ObjectMapper objectMapper, Clock clock) {
        this.concertRepository = concertRepository;
        this.concertSeatRepository = concertSeatRepository;
        this.layoutRepository = layoutRepository;
        this.seatCache = seatCache;
        this.queueService = queueService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 좌석 화면 — 배치 · 상태는 캐시(2초), 타이머는 요청마다. 누가 잡았는지는 내보내지 않습니다.
     * 입장 토큰을 보내면 먼저 확인합니다 (만료 · 다른 토큰이면 대기열로 돌려보냄). 안 보내면 타이머 없이 배치만 (대기열을 끈 공연)
     */
    public SeatMap seats(long concertId, long userId, String entryToken) {
        Long left = entryToken == null || entryToken.isBlank() ? null : queueService.entryLeft(concertId, userId, entryToken);
        SeatMap map = seatCache.get(concertId).orElseGet(() -> load(concertId));
        return left == null ? map : map.withTimer(OffsetDateTime.now(clock).plusSeconds(left), left);
    }

    /**
     * 관리자 — 공연장 배치 좌석을 이 공연 판매 좌석으로 (좌석별 가격). 이미 있으면 가격만 바꿉니다.
     * 선점 · 결제 중 · 판매된 좌석은 그대로 둡니다 (skipped) — 예매 금액이 어긋나지 않게.
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
        int saved = concertSeatRepository.upsertPrices(concertId, seatIds,
                seats.stream().map(SeatPriceRequest.SeatPrice::price).toArray(Integer[]::new));
        log.info("concert_seats_priced concertId={} saved={} skipped={}", concertId, saved, seatIds.length - saved);
        return new SeatPriced(saved, seatIds.length - saved);
    }

    /** 공연장의 층 · 구역 전부 + 이 공연에서 파는 좌석 (쿼리 2번 — 구역 모양을 좌석마다 싣지 않게) */
    private SeatMap load(long concertId) {
        ConcertEntity concert = concertRepository.findById(concertId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        Map<Long, List<SeatView>> bySection = new HashMap<>();
        long total = 0;
        long available = 0;
        for (ConcertSeatRepository.SeatMapRow s : concertSeatRepository.findSeatMap(concertId)) {
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
        SeatMap map = new SeatMap(concertId, concert.getMaxSeatsPerUser(), total, available, null, null, floors);
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
