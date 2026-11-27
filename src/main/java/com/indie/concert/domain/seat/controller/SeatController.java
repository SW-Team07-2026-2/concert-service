package com.indie.concert.domain.seat.controller;

import com.indie.concert.domain.seat.service.SeatService;
import com.indie.concert.global.auth.CurrentUser;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 프론트 -> Gateway -> Concert 좌석 배치, 판매 상태와 입장 토큰 타이머 조회. */
@RestController
public class SeatController {

    private final SeatService seatService;

    public SeatController(SeatService seatService) {
        this.seatService = seatService;
    }

    /**
     * 회차별 좌석 배치와 상태, 입장 토큰의 남은 시간을 반환합니다. 회차가 둘 이상이면 sessionId가 필요합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param concertId 공연 ID
     * @param sessionId 조회할 공연 회차 ID
     * @param entryToken 대기열 통과 후 발급된 입장 토큰
     */
    @GetMapping("/api/concerts/{concertId}/seats")
    public ResponseEntity<byte[]> seats(@CurrentUser Long userId, @PathVariable @Positive long concertId,
                                        @RequestParam(required = false) @Positive Long sessionId,
                                        @RequestHeader(value = "X-Entry-Token", required = false) String entryToken) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(seatService.seatsJson(concertId, sessionId, userId, entryToken));
    }
}
