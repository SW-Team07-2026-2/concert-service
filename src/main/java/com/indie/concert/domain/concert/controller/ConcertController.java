package com.indie.concert.domain.concert.controller;

import com.indie.concert.domain.concert.dto.response.ConcertCalendar;
import com.indie.concert.domain.concert.dto.response.ConcertDetail;
import com.indie.concert.domain.concert.dto.response.ConcertSummary;
import com.indie.concert.domain.concert.dto.response.NoticeView;
import com.indie.concert.domain.concert.dto.response.VenueDetail;
import com.indie.concert.domain.concert.dto.response.VenueView;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.Genre;
import com.indie.concert.domain.concert.entity.Region;
import com.indie.concert.domain.concert.service.ConcertNoticeService;
import com.indie.concert.domain.concert.service.ConcertService;
import com.indie.concert.domain.concert.service.VenueService;
import com.indie.concert.global.auth.CurrentUserArgumentResolver;
import com.indie.concert.global.common.response.ApiResponse;
import com.indie.concert.global.common.response.PageResult;
import com.indie.concert.global.common.util.ListSort;
import com.indie.concert.global.common.util.PageSize;
import com.indie.concert.global.event.UserMetrics;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 프론트 -> Gateway -> Concert 공연, 공연장과 일정 조회 API. 로그인 없이 조회할 수 있습니다. */
@RestController
public class ConcertController {

    private final ConcertService concertService;
    private final VenueService venueService;
    private final ConcertNoticeService noticeService;
    private final PageSize pageSize;
    private final UserMetrics userMetrics;

    public ConcertController(ConcertService concertService, VenueService venueService, ConcertNoticeService noticeService,
                             PageSize pageSize, UserMetrics userMetrics) {
        this.concertService = concertService;
        this.venueService = venueService;
        this.noticeService = noticeService;
        this.pageSize = pageSize;
        this.userMetrics = userMetrics;
    }

    /**
     * 팔로우 탭 - region, genre, type, saleStatus, artistIds 는 여러 개 (region=SEOUL&region=BUSAN 또는 region=SEOUL,BUSAN).
     * saleStatus 를 고르면 지난 공연(CLOSED)도 나옵니다, q 는 공연 제목, 라인업 아티스트 이름, artistId(하나)는 기존 그대로
     *
     * @param from 공연 조회 시작일
     * @param to 공연 조회 종료일
     * @param region 공연장 지역 필터
     * @param genre 장르 필터 목록
     * @param type 처리할 종류
     * @param saleStatus 판매 상태 필터 목록
     * @param artistId 아티스트 ID
     * @param artistIds 대상 아티스트 ID 목록
     * @param q 검색어
     * @param sort 목록 정렬 기준
     * @param page 0부터 시작하는 페이지 번호
     * @param size 페이지당 조회 개수
     */
    @GetMapping("/api/concerts")
    public ApiResponse<PageResult<ConcertSummary>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Region> region,
            @RequestParam(required = false) List<Genre> genre,
            @RequestParam(required = false) List<ConcertEntity.Type> type,
            @RequestParam(required = false) List<ConcertEntity.SaleStatus> saleStatus,
            @RequestParam(required = false) @Positive Long artistId,
            @RequestParam(required = false) List<Long> artistIds,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) ListSort sort,
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.onSuccess(concertService.list(from, to, region, genre, type, saleStatus, artistId, artistIds, q, sort,
                page, pageSize.resolve(size)));
    }

    /**
     * 전체 검색 (프론트 요구 1-3, 2-2) - 공연 제목, 라인업 아티스트 이름, 공연장 이름 LIKE + 진행 현황, 유형, 지역, 장르(모두 여러 개, OR).
     * q 가 없으면 칩만으로. saleStatus 를 안 고르면 지금 이후 공연만, 고르면 지난 공연(CLOSED)도. 공연일 가까운 순
     * 로그인했으면 검색어를 user.metric 으로 남깁니다.
     *
     * @param q 검색어
     * @param region 공연장 지역 필터
     * @param genre 장르 필터 목록
     * @param type 처리할 종류
     * @param saleStatus 판매 상태 필터 목록
     * @param page 0부터 시작하는 페이지 번호
     * @param size 페이지당 조회 개수
     * @param userId 조회하거나 처리할 회원 ID
     */
    @GetMapping("/api/concerts/search")
    public ApiResponse<PageResult<ConcertSummary>> search(@RequestParam(required = false) @Size(max = 100) String q,
                                                          @RequestParam(required = false) List<Region> region,
                                                          @RequestParam(required = false) List<Genre> genre,
                                                          @RequestParam(required = false) List<ConcertEntity.Type> type,
                                                          @RequestParam(required = false) List<ConcertEntity.SaleStatus> saleStatus,
                                                          @RequestParam(defaultValue = "0") @PositiveOrZero int page,
                                                          @RequestParam(required = false) Integer size,
                                                          @RequestHeader(value = CurrentUserArgumentResolver.HEADER, required = false) Long userId) {
        PageResult<ConcertSummary> result = concertService.list(null, null, region, genre, type, saleStatus, null, null, q, null,
                page, pageSize.resolve(size));
        userMetrics.searched(userId, q, page);
        return ApiResponse.onSuccess(result);
    }

    /**
     * 공연 상세를 조회합니다.
     *
     * @param concertId 공연 ID
     */
    @GetMapping("/api/concerts/{concertId}")
    public ApiResponse<ConcertDetail> get(@PathVariable @Positive long concertId) {
        return ApiResponse.onSuccess(concertService.getPublic(concertId));   // 3초 캐시
    }

    /**
     * 공연 공지 목록을 조회합니다.
     *
     * @param concertId 공연 ID
     */
    @GetMapping("/api/concerts/{concertId}/notices")
    public ApiResponse<List<NoticeView>> notices(@PathVariable @Positive long concertId) {
        return ApiResponse.onSuccess(noticeService.list(concertId));
    }

    /**
     * 월별 공연 일정을 조회합니다.
     *
     * @param month 조회할 연월(yyyy-MM)
     * @param artistIds 대상 아티스트 ID 목록
     */
    @GetMapping("/api/concerts/calendar")
    public ApiResponse<ConcertCalendar> calendar(@RequestParam String month,
                                            @RequestParam(required = false) List<Long> artistIds) {
        if (artistIds != null) pageSize.checkIds(artistIds);
        return ApiResponse.onSuccess(concertService.calendar(month, artistIds));
    }

    /**
     * 조건에 맞는 공연장을 조회합니다.
     *
     * @param region 공연장 지역 필터
     * @param q 검색어
     * @param hasUpcoming 예정 공연이 있는 공연장만 조회할지 여부
     * @param page 0부터 시작하는 페이지 번호
     * @param size 페이지당 조회 개수
     */
    @GetMapping("/api/concerts/venues")
    public ApiResponse<PageResult<VenueView>> venues(@RequestParam(required = false) Region region,
                                                       @RequestParam(required = false) @Size(max = 100) String q,
                                                       @RequestParam(defaultValue = "false") boolean hasUpcoming,
                                                       @RequestParam(defaultValue = "0") @PositiveOrZero int page,
                                                       @RequestParam(required = false) Integer size) {
        return ApiResponse.onSuccess(venueService.list(region, q, hasUpcoming, page, pageSize.resolve(size)));
    }

    /**
     * 공연장 상세를 조회합니다.
     *
     * @param venueId 공연장 ID
     */
    @GetMapping("/api/concerts/venues/{venueId}")
    public ApiResponse<VenueDetail> venue(@PathVariable @Positive long venueId) {
        return ApiResponse.onSuccess(venueService.get(venueId));
    }
}
