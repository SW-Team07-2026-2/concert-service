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

/** Studio 공연 관리자 -> Concert 내부 API로 회차별 판매 좌석과 가격을 설정합니다. 선점 또는 판매 좌석은 유지합니다. */
@RestController
public class InternalSeatController {

    private final SeatService seatService;

    public InternalSeatController(SeatService seatService) {
        this.seatService = seatService;
    }

    /**
     * 판매 좌석과 좌석별 가격을 설정합니다.
     *
     * @param concertId 공연 ID
     * @param request 판매 좌석과 가격 목록
     */
    @PostMapping("/internal/admin/concerts/{concertId}/seats")
    public ApiResponse<SeatPriced> price(@PathVariable @Positive long concertId, @Valid @RequestBody SeatPriceRequest request) {
        return ApiResponse.onSuccess(seatService.price(concertId, request.seats()));
    }
}
