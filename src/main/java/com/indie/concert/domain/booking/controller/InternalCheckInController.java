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

/**
 * 내부 — 검표 (11/01 · I24). Studio 의 검표 화면(TICKET_CHECKER · S40 · S41)이 부를 자리입니다 (24 §1 · §9).
 * X-Internal-Token(iss=studio) 필수. 검표자 id 는 본문 값입니다 — X-User-Id 를 신뢰하지 않습니다.
 */
@RestController
public class InternalCheckInController {

    private final EntryService entryService;

    public InternalCheckInController(EntryService entryService) {
        this.entryService = entryService;
    }

    @GetMapping("/internal/admin/concerts/check-in")
    public ApiResponse<List<ConcertRef>> concerts() {
        return ApiResponse.onSuccess(entryService.openConcerts());
    }

    @PostMapping("/internal/admin/concerts/{id}/check-in")
    public ApiResponse<CheckInResult> checkIn(@PathVariable @Positive long id, @Valid @RequestBody CheckInRequest request) {
        return ApiResponse.onSuccess(entryService.checkIn(id, request.token(), request.checkerId()));
    }
}
