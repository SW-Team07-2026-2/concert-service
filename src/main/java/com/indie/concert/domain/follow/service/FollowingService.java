package com.indie.concert.domain.follow.service;

import com.indie.concert.domain.concert.dto.response.ConcertSummary;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.Genre;
import com.indie.concert.domain.concert.entity.Region;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.concert.service.ConcertService;
import com.indie.concert.domain.follow.client.MemberClient;
import com.indie.concert.domain.follow.client.MemberClient.FollowedArtist;
import com.indie.concert.domain.follow.dto.request.FollowSort;
import com.indie.concert.domain.follow.exception.FollowErrorCode;
import com.indie.concert.global.common.exception.CommonErrorCode;
import com.indie.concert.global.common.exception.GeneralException;
import com.indie.concert.global.common.response.PageResult;
import com.indie.concert.global.common.util.ListSort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 공연 팔로우 탭 (11/19 · 프론트 요구 1-1 · 1-2 · 1-3) — 로그인 회원이 팔로우한 아티스트가 라인업에 있는 공연만, 칩 · 검색어를 함께.
 * 팔로우 목록은 Member 내부 API (최근 팔로우순 · 팔로워 수). 최근 팔로우순 · 팔로워순은 그 아티스트 순서로(ARTIST_ORDER),
 * 업데이트순은 공연이 최근에 등록 · 수정된 순(UPDATED). 팔로우가 없으면 DB 를 부르지 않고 빈 페이지.
 */
@Service
public class FollowingService {

    /** 내부 API 한 번에 받는 아티스트 수 상한 (Member 가 넘기는 팔로우 상한과 같음) */
    static final int MAX_NEWS_ARTISTS = 1000;

    private final MemberClient memberClient;
    private final ConcertService concertService;
    private final ConcertRepository concertRepository;

    public FollowingService(MemberClient memberClient, ConcertService concertService, ConcertRepository concertRepository) {
        this.memberClient = memberClient;
        this.concertService = concertService;
        this.concertRepository = concertRepository;
    }

    /** 내부 API (Catalog 팔로우 탭 아티스트 업데이트순) — 아티스트별 가장 최근 공연 등록 · 수정 시각 · 공연 없는 아티스트는 빠짐 */
    @Transactional(readOnly = true)
    public List<ArtistLatest> latestByArtist(List<Long> artistIds) {
        if (artistIds == null || artistIds.isEmpty()) return List.of();
        if (artistIds.size() > MAX_NEWS_ARTISTS) {
            throw new GeneralException(CommonErrorCode.INVALID_REQUEST, "artistIds 는 최대 " + MAX_NEWS_ARTISTS + "개입니다");
        }
        return concertRepository.findLatestUpdatedByArtist(artistIds).stream()
                .map(r -> new ArtistLatest((Long) r[0], (OffsetDateTime) r[1])).toList();
    }

    public record ArtistLatest(long artistId, OffsetDateTime latestAt) {}

    public PageResult<ConcertSummary> concerts(long userId, String q, List<Region> regions, List<Genre> genres,
                                               List<ConcertEntity.Type> types, List<ConcertEntity.SaleStatus> saleStatuses,
                                               FollowSort sort, int page, int size) {
        List<FollowedArtist> follows;
        try {
            follows = memberClient.follows(userId);
        } catch (MemberClient.Unavailable e) {
            throw new GeneralException(FollowErrorCode.FOLLOW_UNAVAILABLE);
        }
        if (follows.isEmpty()) return new PageResult<>(List.of(), page, size, false);
        List<Long> ids = (sort == FollowSort.FOLLOWERS
                ? follows.stream().sorted(Comparator.comparingLong(FollowedArtist::followerCount).reversed())
                : follows.stream()).map(FollowedArtist::artistId).toList();
        return concertService.listOf(null, null, regions, genres, types, saleStatuses, ids, q,
                sort == FollowSort.UPDATED ? ListSort.UPDATED : ListSort.ARTIST_ORDER, page, size);
    }
}
