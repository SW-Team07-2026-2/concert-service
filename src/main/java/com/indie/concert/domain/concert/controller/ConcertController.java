package com.indie.concert.domain.concert.controller;

import com.indie.concert.domain.concert.dto.response.ConcertCalendar;
import com.indie.concert.domain.concert.dto.response.ConcertDetail;
import com.indie.concert.domain.concert.dto.response.ConcertSummary;
import com.indie.concert.domain.concert.dto.response.VenueDetail;
import com.indie.concert.domain.concert.dto.response.VenueView;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.Genre;
import com.indie.concert.domain.concert.entity.Region;
import com.indie.concert.domain.concert.service.ConcertService;
import com.indie.concert.domain.concert.service.VenueService;
import com.indie.concert.global.common.response.ApiResponse;
import com.indie.concert.global.common.response.PageResult;
import com.indie.concert.global.common.util.PageSize;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 공개 API N1 ~ N3 · N14 · N15 — 비로그인 허용 (Gateway 가 GET /api/concerts/** 를 공개로 둡니다) */
@RestController
public class ConcertController {

    private final ConcertService concertService;
    private final VenueService venueService;
    private final PageSize pageSize;

    public ConcertController(ConcertService concertService, VenueService venueService, PageSize pageSize) {
        this.concertService = concertService;
        this.venueService = venueService;
        this.pageSize = pageSize;
    }

    @GetMapping("/api/concerts")
    public ApiResponse<PageResult<ConcertSummary>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Region region,
            @RequestParam(required = false) Genre genre,
            @RequestParam(required = false) ConcertEntity.Type type,
            @RequestParam(required = false) @Positive Long artistId,
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.onSuccess(concertService.list(from, to, region, genre, type, artistId, page, pageSize.resolve(size)));
    }

    @GetMapping("/api/concerts/{id}")
    public ApiResponse<ConcertDetail> get(@PathVariable @Positive long id) {
        return ApiResponse.onSuccess(concertService.get(id));
    }

    /** ?month=2026-11&artistIds=1,2 */
    @GetMapping("/api/concerts/calendar")
    public ApiResponse<ConcertCalendar> calendar(@RequestParam String month,
                                            @RequestParam(required = false) List<Long> artistIds) {
        if (artistIds != null) pageSize.checkIds(artistIds);
        return ApiResponse.onSuccess(concertService.calendar(month, artistIds));
    }

    @GetMapping("/api/concerts/venues")
    public ApiResponse<PageResult<VenueView>> venues(@RequestParam(required = false) Region region,
                                                       @RequestParam(required = false) String q,
                                                       @RequestParam(defaultValue = "false") boolean hasUpcoming,
                                                       @RequestParam(defaultValue = "0") @PositiveOrZero int page,
                                                       @RequestParam(required = false) Integer size) {
        return ApiResponse.onSuccess(venueService.list(region, q, hasUpcoming, page, pageSize.resolve(size)));
    }

    @GetMapping("/api/concerts/venues/{id}")
    public ApiResponse<VenueDetail> venue(@PathVariable @Positive long id) {
        return ApiResponse.onSuccess(venueService.get(id));
    }
}
