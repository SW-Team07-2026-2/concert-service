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
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 로그인한 프론트 -> Gateway -> Concert -> Member 팔로우 조회 후 공연 목록을 반환합니다. */
@RestController
public class FollowingController {

    private final FollowingService followingService;
    private final PageSize pageSize;

    public FollowingController(FollowingService followingService, PageSize pageSize) {
        this.followingService = followingService;
        this.pageSize = pageSize;
    }

    /**
     * 팔로우한 아티스트가 참여하는 공연을 조회합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param q 검색어
     * @param region 공연장 지역 필터
     * @param genre 장르 필터 목록
     * @param type 처리할 종류
     * @param saleStatus 판매 상태 필터 목록
     * @param sort 목록 정렬 기준
     * @param page 0부터 시작하는 페이지 번호
     * @param size 페이지당 조회 개수
     */
    @GetMapping("/api/concerts/following")
    public ApiResponse<PageResult<ConcertSummary>> concerts(@CurrentUser Long userId,
                                                            @RequestParam(required = false) @Size(max = 100) String q,
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
