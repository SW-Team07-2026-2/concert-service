package com.indie.concert.domain.layout.controller;

import com.indie.concert.domain.layout.dto.request.SectionLayoutRequest;
import com.indie.concert.domain.layout.dto.response.SectionSaved;
import com.indie.concert.domain.layout.dto.response.VenueLayout;
import com.indie.concert.domain.layout.service.LayoutService;
import com.indie.concert.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 — 공연장 배치 저장 · 조회 (관리자 무대 그리기). Studio 가 GPU 서버 결과를 정제해 구역 하나씩 보냅니다.
 * X-Internal-Token(iss=studio) 필수 · camelCase.
 */
@RestController
public class InternalLayoutController {

    private final LayoutService layoutService;

    public InternalLayoutController(LayoutService layoutService) {
        this.layoutService = layoutService;
    }

    /** 같은 층 · 구역 이름으로 다시 보내면 덮어씁니다. 그 구역 좌석을 파는 공연이 있으면 409 SECTION_IN_USE */
    @PutMapping("/internal/admin/concerts/venues/{venueId}/sections")
    public ApiResponse<SectionSaved> saveSection(@PathVariable @Positive long venueId,
                                                 @Valid @RequestBody SectionLayoutRequest request) {
        return ApiResponse.onSuccess(layoutService.saveSection(venueId, request));
    }

    /** 저장된 배치 — 무대 탭의 현재 배치 · 공연 탭의 좌석 가격 화면 (좌석 id = 좌석 가격 API 의 seatId) */
    @GetMapping("/internal/admin/concerts/venues/{venueId}/layout")
    public ApiResponse<VenueLayout> layout(@PathVariable @Positive long venueId) {
        return ApiResponse.onSuccess(layoutService.layout(venueId));
    }
}
