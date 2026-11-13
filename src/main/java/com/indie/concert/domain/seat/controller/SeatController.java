package com.indie.concert.domain.seat.controller;

import com.indie.concert.domain.seat.dto.response.SeatMap;
import com.indie.concert.domain.seat.service.SeatService;
import com.indie.concert.global.auth.CurrentUser;
import com.indie.concert.global.common.response.ApiResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 API — 좌석 화면 (배치 · 상태 · 타이머). 로그인 필요.
 * 좌석 선점은 예매 만들기(POST /api/concerts/{id}/bookings)가 합니다 — 해제는 예매 취소(POST /api/concerts/bookings/{id}/cancel)입니다.
 */
@RestController
public class SeatController {

    private final SeatService seatService;

    public SeatController(SeatService seatService) {
        this.seatService = seatService;
    }

    /**
     * 대기열을 통과해 무대 화면에 들어오면 입장 토큰(X-Entry-Token)과 함께 부릅니다 — 응답의 expiresIn(남은 초)으로 타이머를 돌립니다.
     * 토큰이 만료됐으면 403 QUEUE_NOT_ADMITTED 등 대기열 에러 — 대기열로 돌아갑니다. 대기열을 끈 공연은 토큰 없이 (타이머 없음)
     */
    @GetMapping("/api/concerts/{id}/seats")
    public ApiResponse<SeatMap> seats(@CurrentUser Long userId, @PathVariable @Positive long id,
                                      @RequestHeader(value = "X-Entry-Token", required = false) String entryToken) {
        return ApiResponse.onSuccess(seatService.seats(id, userId, entryToken));
    }
}
