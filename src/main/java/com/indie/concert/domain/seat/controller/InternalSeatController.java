package com.indie.concert.domain.seat.controller;

import com.indie.concert.domain.seat.dto.request.SeatPriceRequest;
import com.indie.concert.domain.seat.dto.response.SeatPriced;
import com.indie.concert.domain.seat.service.SeatService;
import com.indie.concert.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 — 관리자 공연 좌석 가격. 공연장 배치(무대 그리기로 저장한 좌석) 중 이 공연에서 팔 좌석과 좌석별 가격을 받습니다.
 * X-Internal-Token(iss=studio) 필수 · camelCase. 다시 보내면 가격을 바꿉니다 (선점 · 판매된 좌석 제외).
 */
@RestController
public class InternalSeatController {

    private final SeatService seatService;

    public InternalSeatController(SeatService seatService) {
        this.seatService = seatService;
    }

    @PostMapping("/internal/admin/concerts/{id}/seats")
    public ApiResponse<SeatPriced> price(@PathVariable @Positive long id, @Valid @RequestBody SeatPriceRequest request) {
        return ApiResponse.onSuccess(seatService.price(id, request.seats()));
    }
}
