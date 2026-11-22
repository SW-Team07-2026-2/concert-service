package com.indie.concert.domain.concert.controller;

import com.indie.concert.domain.concert.dto.request.ConcertCreateRequest;
import com.indie.concert.domain.concert.dto.request.ConcertUpdateRequest;
import com.indie.concert.domain.concert.dto.request.LineupRequest;
import com.indie.concert.domain.concert.dto.request.NoticeCreateRequest;
import com.indie.concert.domain.concert.dto.request.NoticeUpdateRequest;
import com.indie.concert.domain.concert.dto.request.VenueCreateRequest;
import com.indie.concert.domain.concert.dto.response.ConcertDetail;
import com.indie.concert.domain.concert.dto.response.NoticeView;
import com.indie.concert.domain.concert.dto.response.VenueView;
import com.indie.concert.domain.concert.service.ConcertAdminService;
import com.indie.concert.domain.concert.service.ConcertNoticeService;
import com.indie.concert.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 내부 — 관리자 공연장 · 공연 등록과 수정. Studio 가 검수를 마친 데이터만 보냅니다 (수집 데이터도 Studio 를 거침).
 * X-Internal-Token(iss=studio) 필수 · camelCase. 공연장 배치는 InternalLayoutController, 좌석 가격은 InternalSeatController.
 */
@RestController
public class InternalAdminConcertController {

    private final ConcertAdminService concertAdminService;
    private final ConcertNoticeService noticeService;

    public InternalAdminConcertController(ConcertAdminService concertAdminService, ConcertNoticeService noticeService) {
        this.concertAdminService = concertAdminService;
        this.noticeService = noticeService;
    }

    @PostMapping("/internal/admin/concerts/venues")
    public ApiResponse<VenueView> createVenue(@Valid @RequestBody VenueCreateRequest request) {
        return ApiResponse.onSuccess(concertAdminService.createVenue(request));
    }

    @PostMapping("/internal/admin/concerts")
    public ApiResponse<ConcertDetail> create(@Valid @RequestBody ConcertCreateRequest request) {
        return ApiResponse.onSuccess(concertAdminService.create(request));
    }

    @PatchMapping("/internal/admin/concerts/{id}")
    public ApiResponse<ConcertDetail> update(@PathVariable @Positive long id, @Valid @RequestBody ConcertUpdateRequest request) {
        return ApiResponse.onSuccess(concertAdminService.update(id, request));
    }

    /** 라인업 전체 교체 — 차례로 공개될 때마다 지금까지의 전체 목록을 보냅니다. 새로 추가된 아티스트의 팔로워에게 알림이 갑니다 */
    @PutMapping("/internal/admin/concerts/{id}/lineup")
    public ApiResponse<ConcertDetail> replaceLineup(@PathVariable @Positive long id, @Valid @RequestBody LineupRequest request) {
        return ApiResponse.onSuccess(concertAdminService.replaceLineup(id, request.artistIds()));
    }

    // ── 공연 변경 공지 (11/18) — 새 공지만 알림 (라인업 팔로워 + 확정 예매자) ──

    @GetMapping("/internal/admin/concerts/{id}/notices")
    public ApiResponse<List<NoticeView>> notices(@PathVariable @Positive long id) {
        return ApiResponse.onSuccess(noticeService.list(id));
    }

    @PostMapping("/internal/admin/concerts/{id}/notices")
    public ApiResponse<NoticeView> createNotice(@PathVariable @Positive long id, @Valid @RequestBody NoticeCreateRequest request) {
        return ApiResponse.onSuccess(noticeService.create(id, request));
    }

    @PatchMapping("/internal/admin/concerts/{id}/notices/{noticeId}")
    public ApiResponse<NoticeView> updateNotice(@PathVariable @Positive long id, @PathVariable @Positive long noticeId,
                                                @Valid @RequestBody NoticeUpdateRequest request) {
        return ApiResponse.onSuccess(noticeService.update(id, noticeId, request));
    }

    @DeleteMapping("/internal/admin/concerts/{id}/notices/{noticeId}")
    public ApiResponse<Void> deleteNotice(@PathVariable @Positive long id, @PathVariable @Positive long noticeId) {
        noticeService.delete(id, noticeId);
        return ApiResponse.onSuccess(null);
    }
}
