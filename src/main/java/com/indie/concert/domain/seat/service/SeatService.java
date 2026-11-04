package com.indie.concert.domain.seat.service;

import com.indie.concert.domain.booking.repository.BookingRepository;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.seat.dto.response.SeatMap;
import com.indie.concert.domain.seat.dto.response.SeatSection;
import com.indie.concert.domain.seat.dto.response.SeatView;
import com.indie.concert.domain.seat.entity.SeatEntity;
import com.indie.concert.domain.seat.repository.SeatCache;
import com.indie.concert.domain.seat.repository.SeatRepository;
import com.indie.concert.global.common.exception.CommonErrorCode;
import com.indie.concert.global.common.exception.GeneralException;
import com.indie.concert.global.config.ConcertProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 좌석 — 배치 조회(N7) · 일괄 생성(내부). 좌석을 고르고(N10) 결제 승인(N16)하는 흐름은 BookingService 에 있습니다.
 * <pre>
 * 화면 상태 (10/27 · FR-CON-23)
 *   AVAILABLE  미선택   아무도 고르지 않음
 *   CONTESTED  경합     누군가 골랐지만 결제 전 (여러 명 가능) · 결제 승인 중 — 그래도 고를 수 있음
 *   SOLD       선택완료  먼저 결제 승인한 사람에게 팔림
 * </pre>
 */
@Service
public class SeatService {

    private static final int MAX_SEATS_PER_REQUEST = 10_000;

    private final ConcertRepository concertRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final SeatCache seatCache;
    private final ConcertProperties.Booking props;
    private final Clock clock;

    public SeatService(ConcertRepository concertRepository, SeatRepository seatRepository, BookingRepository bookingRepository,
                       SeatCache seatCache, ConcertProperties props, Clock clock) {
        this.concertRepository = concertRepository;
        this.seatRepository = seatRepository;
        this.bookingRepository = bookingRepository;
        this.seatCache = seatCache;
        this.props = props.booking();
        this.clock = clock;
    }

    /** N7 — 좌석 배치 · 상태 (캐시 2초). 누가 · 몇 명이 골랐는지는 내보내지 않습니다. available = 미선택 + 경합 (아직 살 수 있는 좌석) */
    public SeatMap seats(long concertId) {
        return seatCache.get(concertId).orElseGet(() -> {
            concertRepository.findById(concertId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
            OffsetDateTime selectedAfter = OffsetDateTime.now(clock).minusMinutes(props.selectMinutes());
            Set<Long> contested = new HashSet<>(bookingRepository.findPendingSeatIds(concertId, selectedAfter));
            List<SeatView> seats = seatRepository.findByConcertIdOrderById(concertId).stream()
                    .map(s -> new SeatView(s.getId(), s.getSection(), s.getRowNo(), s.getSeatNo(), s.getPrice(), status(s, contested)))
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

    private static String status(SeatEntity s, Set<Long> contested) {
        if (s.getStatus() == SeatEntity.Status.SOLD) return "SOLD";
        return s.getStatus() == SeatEntity.Status.HELD || contested.contains(s.getId()) ? "CONTESTED" : "AVAILABLE";
    }
}
