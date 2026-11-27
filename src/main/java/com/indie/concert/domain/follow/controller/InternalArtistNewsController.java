package com.indie.concert.domain.follow.controller;

import com.indie.concert.domain.follow.service.FollowingService;
import com.indie.concert.domain.follow.service.FollowingService.ArtistLatest;
import com.indie.concert.global.common.response.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Catalog -> Concert 내부 API로 아티스트별 최근 공연 등록 또는 수정 시각을 조회합니다. */
@RestController
public class InternalArtistNewsController {

    private final FollowingService followingService;

    public InternalArtistNewsController(FollowingService followingService) {
        this.followingService = followingService;
    }

    /**
     * 아티스트별 가장 최근 공연 등록 또는 수정 시각을 반환합니다.
     *
     * @param request 조회할 아티스트 ID 목록
     */
    @PostMapping("/internal/concerts/artists/latest")
    public ApiResponse<Map<String, List<ArtistLatest>>> latest(@RequestBody Request request) {
        return ApiResponse.onSuccess(Map.of("items", followingService.latestByArtist(request.artistIds())));
    }

    public record Request(List<Long> artistIds) {}
}
