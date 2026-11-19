package com.indie.concert.domain.follow.controller;

import com.indie.concert.domain.concert.dto.response.ConcertSummary;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.Genre;
import com.indie.concert.domain.concert.entity.Region;
import com.indie.concert.domain.follow.dto.request.FollowSort;
import com.indie.concert.domain.follow.service.FollowingService;
import com.indie.concert.global.auth.CurrentUser;
import com.indie.concert.global.common.response.ApiResponse;
import com.indie.concert.global.common.response.PageResult;
import com.indie.concert.global.common.util.PageSize;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 11/19 공연 팔로우 탭 (프론트 요구 1-1 · 1-2 · 1-3 · 2-2) — 로그인 필요 · 내가 팔로우한 아티스트가 나오는 공연만.
 * q 는 선택(공연 제목 · 라인업 아티스트 이름 · 공연장 이름 LIKE). 칩은 여러 개(같은 칩 안은 OR, 서로 다른 칩끼리는 AND).
 * saleStatus 를 안 고르면 지금 이후 공연만 · 고르면 지난 공연(CLOSED)도. sort — RECENT_FOLLOW(기본) · FOLLOWERS · UPDATED
 */
@RestController
public class FollowingController {

    private final FollowingService followingService;
    private final PageSize pageSize;

    public FollowingController(FollowingService followingService, PageSize pageSize) {
        this.followingService = followingService;
        this.pageSize = pageSize;
    }

    @GetMapping("/api/concerts/following")
    public ApiResponse<PageResult<ConcertSummary>> concerts(@CurrentUser Long userId,
                                                            @RequestParam(required = false) String q,
                                                            @RequestParam(required = false) List<Region> region,
                                                            @RequestParam(required = false) List<Genre> genre,
                                                            @RequestParam(required = false) List<ConcertEntity.Type> type,
                                                            @RequestParam(required = false) List<ConcertEntity.SaleStatus> saleStatus,
                                                            @RequestParam(required = false) FollowSort sort,
                                                            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
                                                            @RequestParam(required = false) Integer size) {
        return ApiResponse.onSuccess(followingService.concerts(userId, q, region, genre, type, saleStatus, sort, page, pageSize.resolve(size)));
    }
}
