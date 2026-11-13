package com.indie.concert.domain.layout.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.concert.domain.concert.repository.VenueRepository;
import com.indie.concert.domain.layout.dto.request.SeatPoint;
import com.indie.concert.domain.layout.dto.request.SectionLayoutRequest;
import com.indie.concert.domain.layout.dto.response.SectionSaved;
import com.indie.concert.domain.layout.exception.LayoutErrorCode;
import com.indie.concert.domain.layout.repository.LayoutRepository;
import com.indie.concert.global.common.exception.CommonErrorCode;
import com.indie.concert.global.common.exception.GeneralException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 공연장 배치 — 관리자(Studio)의 무대 그리기 결과를 구역 단위로 저장합니다. 공연장 → 층 → 구역 → 좌석.
 * 같은 구역을 다시 보내면 모양 · 기울기를 덮어쓰고 좌석은 지운 뒤 새로 넣습니다.
 * 🔴 이 구역 좌석을 파는 공연(concert_seat)이 있으면 409 — 좌석 id 가 바뀌면 판매 · 예매 기록이 끊깁니다.
 */
@Service
public class LayoutService {

    private static final Logger log = LoggerFactory.getLogger(LayoutService.class);

    private final VenueRepository venueRepository;
    private final LayoutRepository layoutRepository;
    private final ObjectMapper objectMapper;

    public LayoutService(VenueRepository venueRepository, LayoutRepository layoutRepository, ObjectMapper objectMapper) {
        this.venueRepository = venueRepository;
        this.layoutRepository = layoutRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SectionSaved saveSection(long venueId, SectionLayoutRequest request) {
        if (request.venueId() != null && request.venueId() != venueId) {
            throw new GeneralException(CommonErrorCode.INVALID_REQUEST, "본문의 venueId 가 경로와 다릅니다");
        }
        venueRepository.findById(venueId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        List<SeatPoint> seats = request.seats();
        Integer seatCount = request.section().seatCount();
        if (seatCount != null && seatCount != seats.size()) {
            throw new GeneralException(CommonErrorCode.INVALID_REQUEST, "seatCount 와 seats 개수가 다릅니다");
        }
        Set<String> positions = new HashSet<>();
        for (SeatPoint s : seats) {
            if (!positions.add(s.row() + "-" + s.number())) {
                throw new GeneralException(CommonErrorCode.INVALID_REQUEST, s.row() + "열 " + s.number() + "번 좌석이 두 번 있습니다");
            }
        }
        long floorId = layoutRepository.upsertFloor(venueId, request.floor().strip());
        long sectionId = layoutRepository.upsertSection(floorId, request.section().name().strip(),
                json(request.section().shape()), request.section().angle());
        if (layoutRepository.sectionInUse(sectionId)) throw new GeneralException(LayoutErrorCode.SECTION_IN_USE);   // 롤백 — 모양도 그대로
        layoutRepository.deleteSeats(sectionId);
        int saved = layoutRepository.insertSeats(sectionId,
                seats.stream().map(SeatPoint::row).toArray(Integer[]::new),
                seats.stream().map(SeatPoint::number).toArray(Integer[]::new),
                seats.stream().map(SeatPoint::x).toArray(BigDecimal[]::new),
                seats.stream().map(SeatPoint::y).toArray(BigDecimal[]::new),
                seats.stream().map(SeatPoint::angle).toArray(BigDecimal[]::new));
        log.info("layout_section_saved venueId={} floorId={} sectionId={} seats={}", venueId, floorId, sectionId, saved);
        return new SectionSaved(venueId, floorId, sectionId, saved);
    }

    private String json(List<List<BigDecimal>> shape) {
        try {
            return objectMapper.writeValueAsString(shape);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);   // 숫자 목록이라 생기지 않습니다
        }
    }
}
