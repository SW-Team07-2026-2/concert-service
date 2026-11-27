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

/** Studio 무대 관리자 -> Concert 내부 API로 공연장 구역 배치를 저장하거나 조회합니다. */
@RestController
public class InternalLayoutController {

    private final LayoutService layoutService;

    public InternalLayoutController(LayoutService layoutService) {
        this.layoutService = layoutService;
    }

    /**
     * 공연장 구역과 좌석 배치를 저장합니다.
     *
     * @param venueId 공연장 ID
     * @param request 공연장 구역과 좌석 배치
     */
    @PutMapping("/internal/admin/concerts/venues/{venueId}/sections")
    public ApiResponse<SectionSaved> saveSection(@PathVariable @Positive long venueId,
                                                 @Valid @RequestBody SectionLayoutRequest request) {
        return ApiResponse.onSuccess(layoutService.saveSection(venueId, request));
    }

    /**
     * 저장된 배치 - 무대 탭의 현재 배치, 공연 탭의 좌석 가격 화면 (좌석 id = 좌석 가격 API 의 seatId)
     *
     * @param venueId 공연장 ID
     */
    @GetMapping("/internal/admin/concerts/venues/{venueId}/layout")
    public ApiResponse<VenueLayout> layout(@PathVariable @Positive long venueId) {
        return ApiResponse.onSuccess(layoutService.layout(venueId));
    }
}
