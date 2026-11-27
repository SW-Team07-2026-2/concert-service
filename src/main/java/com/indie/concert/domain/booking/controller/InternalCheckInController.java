package com.indie.concert.domain.booking.controller;

import com.indie.concert.domain.booking.dto.request.CheckInRequest;
import com.indie.concert.domain.booking.dto.response.CheckInResult;
import com.indie.concert.domain.booking.dto.response.ConcertRef;
import com.indie.concert.domain.booking.service.EntryService;
import com.indie.concert.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Studio 검표 관리자 -> Concert 내부 API로 검표 대상 공연 조회와 QR 입장 처리. */
@RestController
public class InternalCheckInController {

    private final EntryService entryService;

    public InternalCheckInController(EntryService entryService) {
        this.entryService = entryService;
    }

    /** 검표 가능한 공연 목록을 반환합니다. */
    @GetMapping("/internal/admin/concerts/check-in")
    public ApiResponse<List<ConcertRef>> concerts() {
        return ApiResponse.onSuccess(entryService.openConcerts());
    }

    /**
     * QR을 검증하고 한 번만 입장 처리합니다.
     *
     * @param concertId 공연 ID
     * @param request 입장 QR 토큰과 검표자 정보
     */
    @PostMapping("/internal/admin/concerts/{concertId}/check-in")
    public ApiResponse<CheckInResult> checkIn(@PathVariable @Positive long concertId, @Valid @RequestBody CheckInRequest request) {
        return ApiResponse.onSuccess(entryService.checkIn(concertId, request.token(), request.checkerId()));
    }
}
