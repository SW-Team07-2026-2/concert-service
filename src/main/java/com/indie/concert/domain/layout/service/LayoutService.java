package com.indie.concert.domain.layout.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.concert.domain.concert.repository.VenueRepository;
import com.indie.concert.domain.layout.dto.request.SeatPoint;
import com.indie.concert.domain.layout.dto.request.SectionLayoutRequest;
import com.indie.concert.domain.layout.dto.response.SectionSaved;
import com.indie.concert.domain.layout.dto.response.VenueLayout;
import com.indie.concert.domain.layout.exception.LayoutErrorCode;
import com.indie.concert.domain.layout.repository.LayoutRepository;
import com.indie.concert.global.common.exception.CommonErrorCode;
import com.indie.concert.global.common.exception.GeneralException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** 저장된 배치 — 층 · 구역 · 좌석 (쿼리 2번 — 구역 모양을 좌석마다 싣지 않게). 배치가 아직 없으면 floors 가 빈 목록 */
    @Transactional(readOnly = true)
    public VenueLayout layout(long venueId) {
        venueRepository.findById(venueId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
        Map<Long, List<VenueLayout.Seat>> bySection = new HashMap<>();
        for (LayoutRepository.SeatRow s : layoutRepository.findSeats(venueId)) {
            bySection.computeIfAbsent(s.getSectionId(), id -> new ArrayList<>())
                    .add(new VenueLayout.Seat(s.getId(), s.getRowNo(), s.getSeatNo(), s.getX(), s.getY(), s.getAngle()));
        }
        Map<Long, String> floorNames = new LinkedHashMap<>();
        Map<Long, List<VenueLayout.Section>> byFloor = new HashMap<>();
        for (LayoutRepository.SectionRow r : layoutRepository.findSections(venueId)) {
            floorNames.putIfAbsent(r.getFloorId(), r.getFloorName());
            byFloor.computeIfAbsent(r.getFloorId(), id -> new ArrayList<>()).add(new VenueLayout.Section(r.getId(), r.getName(),
                    shape(r.getShape()), r.getAngle(), bySection.getOrDefault(r.getId(), List.of())));
        }
        return new VenueLayout(venueId, floorNames.entrySet().stream()
                .map(f -> new VenueLayout.Floor(f.getKey(), f.getValue(), byFloor.get(f.getKey()))).toList());
    }

    private List<List<BigDecimal>> shape(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("section.shape 가 꼭짓점 목록이 아닙니다", e);   // 저장 때 검증하므로 생기지 않습니다
        }
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
