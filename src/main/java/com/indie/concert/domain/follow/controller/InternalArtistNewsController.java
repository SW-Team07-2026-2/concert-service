package com.indie.concert.domain.follow.controller;

import com.indie.concert.domain.follow.service.FollowingService;
import com.indie.concert.domain.follow.service.FollowingService.ArtistLatest;
import com.indie.concert.global.common.response.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * POST /internal/concerts/artists/latest — Catalog 팔로우 탭 아티스트 업데이트순 (11/19). 발급자는 news-issuers(catalog)만.
 * 본문 { artistIds: [...] } (최대 1000) → { items: [{ artistId, latestAt }] } · ID 가 많아 쿼리 문자열 대신 본문으로 받습니다.
 */
@RestController
public class InternalArtistNewsController {

    private final FollowingService followingService;

    public InternalArtistNewsController(FollowingService followingService) {
        this.followingService = followingService;
    }

    @PostMapping("/internal/concerts/artists/latest")
    public ApiResponse<Map<String, List<ArtistLatest>>> latest(@RequestBody Request request) {
        return ApiResponse.onSuccess(Map.of("items", followingService.latestByArtist(request.artistIds())));
    }

    public record Request(List<Long> artistIds) {}
}
