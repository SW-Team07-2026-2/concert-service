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
 * 점유(N8) · 점유 해제(N9)는 10/27 폐지 — 좌석은 고를 때 잡지 않고 먼저 결제 승인(N16)한 사람이 가져갑니다.
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
