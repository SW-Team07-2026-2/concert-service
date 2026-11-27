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

/** Studio -> Concert 내부 관리자 API로 공연장, 공연, 라인업과 공지를 등록하고 수정합니다. */
@RestController
public class InternalAdminConcertController {

    private final ConcertAdminService concertAdminService;
    private final ConcertNoticeService noticeService;

    public InternalAdminConcertController(ConcertAdminService concertAdminService, ConcertNoticeService noticeService) {
        this.concertAdminService = concertAdminService;
        this.noticeService = noticeService;
    }

    /**
     * 공연장을 등록합니다.
     *
     * @param request 등록할 공연장 정보
     */
    @PostMapping("/internal/admin/concerts/venues")
    public ApiResponse<VenueView> createVenue(@Valid @RequestBody VenueCreateRequest request) {
        return ApiResponse.onSuccess(concertAdminService.createVenue(request));
    }

    /**
     * 공연과 회차를 등록합니다.
     *
     * @param request 등록할 공연과 회차 정보
     */
    @PostMapping("/internal/admin/concerts")
    public ApiResponse<ConcertDetail> create(@Valid @RequestBody ConcertCreateRequest request) {
        return ApiResponse.onSuccess(concertAdminService.create(request));
    }

    /**
     * 공연과 회차 정보를 수정합니다.
     *
     * @param concertId 공연 ID
     * @param request 수정할 공연과 회차 정보
     */
    @PatchMapping("/internal/admin/concerts/{concertId}")
    public ApiResponse<ConcertDetail> update(@PathVariable @Positive long concertId, @Valid @RequestBody ConcertUpdateRequest request) {
        return ApiResponse.onSuccess(concertAdminService.update(concertId, request));
    }

    /**
     * 공연 라인업을 교체합니다.
     *
     * @param concertId 공연 ID
     * @param request 변경할 라인업 아티스트 목록
     */
    @PutMapping("/internal/admin/concerts/{concertId}/lineup")
    public ApiResponse<ConcertDetail> replaceLineup(@PathVariable @Positive long concertId, @Valid @RequestBody LineupRequest request) {
        return ApiResponse.onSuccess(concertAdminService.replaceLineup(concertId, request.artistIds()));
    }

    // ── 공연 변경 공지 - 새 공지만 알림 (라인업 팔로워 + 확정 예매자) ──

    /**
     * InternalAdminConcert 공지 목록을 조회합니다.
     *
     * @param concertId 공연 ID
     */
    @GetMapping("/internal/admin/concerts/{concertId}/notices")
    public ApiResponse<List<NoticeView>> notices(@PathVariable @Positive long concertId) {
        return ApiResponse.onSuccess(noticeService.list(concertId));
    }

    /**
     * 공연 변경 공지를 등록합니다.
     *
     * @param concertId 공연 ID
     * @param request 등록할 공연 공지
     */
    @PostMapping("/internal/admin/concerts/{concertId}/notices")
    public ApiResponse<NoticeView> createNotice(@PathVariable @Positive long concertId, @Valid @RequestBody NoticeCreateRequest request) {
        return ApiResponse.onSuccess(noticeService.create(concertId, request));
    }

    /**
     * 공연 변경 공지를 수정합니다.
     *
     * @param concertId 공연 ID
     * @param noticeId 공연 공지 ID
     * @param request 수정할 공연 공지
     */
    @PatchMapping("/internal/admin/concerts/{concertId}/notices/{noticeId}")
    public ApiResponse<NoticeView> updateNotice(@PathVariable @Positive long concertId, @PathVariable @Positive long noticeId,
                                                @Valid @RequestBody NoticeUpdateRequest request) {
        return ApiResponse.onSuccess(noticeService.update(concertId, noticeId, request));
    }

    /**
     * 공연 변경 공지를 삭제합니다.
     *
     * @param concertId 공연 ID
     * @param noticeId 공연 공지 ID
     */
    @DeleteMapping("/internal/admin/concerts/{concertId}/notices/{noticeId}")
    public ApiResponse<Void> deleteNotice(@PathVariable @Positive long concertId, @PathVariable @Positive long noticeId) {
        noticeService.delete(concertId, noticeId);
        return ApiResponse.onSuccess(null);
    }
}
