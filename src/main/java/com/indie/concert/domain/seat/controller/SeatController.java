package com.indie.concert.domain.seat.controller;

import com.indie.concert.domain.seat.dto.response.SeatMap;
import com.indie.concert.domain.seat.service.SeatService;
import com.indie.concert.global.auth.CurrentUser;
import com.indie.concert.global.common.response.ApiResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 API N7 — 좌석 배치 · 상태. 로그인 필요.
 * 좌석 선점은 N10(예매 만들기)이 합니다 (11/04 77A) — 별도 점유(N8) · 해제(N9) API 는 없고, 해제는 N13(선택 취소)입니다.
 */
@RestController
public class SeatController {

    private final SeatService seatService;

    public SeatController(SeatService seatService) {
        this.seatService = seatService;
    }

    /** 15 N7 — 로그인 필요 (userId 는 인증 확인용으로만 받습니다) */
    @GetMapping("/api/concerts/{id}/seats")
    public ApiResponse<SeatMap> seats(@CurrentUser Long userId, @PathVariable @Positive long id) {
        return ApiResponse.onSuccess(seatService.seats(id));
    }
}
