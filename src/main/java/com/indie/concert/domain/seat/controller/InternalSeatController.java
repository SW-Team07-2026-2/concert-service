package com.indie.concert.domain.seat.controller;

import com.indie.concert.domain.seat.dto.request.SeatGridRequest;
import com.indie.concert.domain.seat.dto.response.SeatCreatedResponse;
import com.indie.concert.domain.seat.dto.response.SeatSection;
import com.indie.concert.domain.seat.service.SeatService;
import com.indie.concert.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 — 좌석 일괄 생성 (15 에 없음 · 추가). 시드 스크립트와 Studio S5(폴리곤 → 좌석)가 부를 자리입니다 (08 §12.4 — 대량 작업은 Concert 쪽에서).
 * X-Internal-Token 필수 · snake_case (호출자가 스크립트).
 */
@RestController
public class InternalSeatController {

    private final SeatService seatService;

    public InternalSeatController(SeatService seatService) {
        this.seatService = seatService;
    }

    @PostMapping("/internal/concerts/{id}/seats")
    public ApiResponse<SeatCreatedResponse> create(@PathVariable @Positive long id, @Valid @RequestBody SeatGridRequest request) {
        return ApiResponse.onSuccess(new SeatCreatedResponse(seatService.createGrid(id, request.sections().stream()
                .map(s -> new SeatSection(s.section(), s.rows(), s.seatsPerRow(), s.price())).toList())));
    }
}
