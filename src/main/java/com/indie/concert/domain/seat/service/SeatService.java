package com.indie.concert.domain.seat.service;

import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.seat.dto.response.SeatMap;
import com.indie.concert.domain.seat.dto.response.SeatSection;
import com.indie.concert.domain.seat.dto.response.SeatView;
import com.indie.concert.domain.seat.entity.SeatEntity;
import com.indie.concert.domain.seat.repository.SeatCache;
import com.indie.concert.domain.seat.repository.SeatRepository;
import com.indie.concert.global.common.exception.CommonErrorCode;
import com.indie.concert.global.common.exception.GeneralException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 좌석 — 배치 조회(N7) · 일괄 생성(내부). 좌석을 고르고(N10) 결제 승인(N16)하는 흐름은 BookingService 에 있습니다.
 * <pre>
 * 화면 상태 (11/04 77A 선점 · FR-CON-23)
 *   AVAILABLE  선택 가능  아무도 잡지 않음 · 선점 마감(DB now())이 지남 — 정리 전이라도 바로 고를 수 있음
 *   HELD       선택 중    누군가 선점(마감 전) · 결제 승인 중(PAYING) — 회색 · 못 고름 (마감 뒤 다시 풀릴 수 있음)
 *   SOLD       판매       결제 완료
 * </pre>
 */
@Service
public class SeatService {

    private static final int MAX_SEATS_PER_REQUEST = 10_000;

    private final ConcertRepository concertRepository;
    private final SeatRepository seatRepository;
    private final SeatCache seatCache;

    public SeatService(ConcertRepository concertRepository, SeatRepository seatRepository, SeatCache seatCache) {
        this.concertRepository = concertRepository;
        this.seatRepository = seatRepository;
        this.seatCache = seatCache;
    }

    /** N7 — 좌석 배치 · 상태 (캐시 2초). 누가 잡았는지는 내보내지 않습니다. available = 판매되지 않은 좌석 (선택 중은 풀릴 수 있어 포함) */
    public SeatMap seats(long concertId) {
        return seatCache.get(concertId).orElseGet(() -> {
            concertRepository.findById(concertId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
            OffsetDateTime now = Instant.ofEpochMilli(seatRepository.nowMillis()).atOffset(ZoneOffset.UTC);   // DB 시계
            List<SeatView> seats = seatRepository.findByConcertIdOrderById(concertId).stream()
                    .map(s -> new SeatView(s.getId(), s.getSection(), s.getRowNo(), s.getSeatNo(), s.getPrice(), status(s, now)))
                    .toList();
            long available = seats.stream().filter(s -> !SeatEntity.Status.SOLD.name().equals(s.status())).count();
            SeatMap map = new SeatMap(concertId, seats.size(), available, seats);
            seatCache.put(map);
            return map;
        });
    }

    /** 내부 — 구역별 rows × seatsPerRow 일괄 생성 (시드 · Studio S5). 이미 있는 자리는 건너뜁니다 */
    @Transactional
    public int createGrid(long concertId, List<SeatSection> sections) {
        concertRepository.findById(concertId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        long total = sections.stream().mapToLong(s -> (long) s.rows() * s.seatsPerRow()).sum();
        if (total > MAX_SEATS_PER_REQUEST) {
            throw new GeneralException(CommonErrorCode.INVALID_REQUEST, "한 번에 최대 " + MAX_SEATS_PER_REQUEST + "석까지 만들 수 있습니다");
        }
        int created = 0;
        for (SeatSection s : sections) {
            created += seatRepository.createGrid(concertId, s.section().strip(), s.rows(), s.seatsPerRow(), s.price());
        }
        return created;
    }

    private static String status(SeatEntity s, OffsetDateTime now) {
        return switch (s.getStatus()) {
            case SOLD -> "SOLD";
            case PAYING -> "HELD";
            case HELD -> s.getHoldExpiresAt() != null && s.getHoldExpiresAt().isAfter(now) ? "HELD" : "AVAILABLE";
            case AVAILABLE -> "AVAILABLE";
        };
    }
}
