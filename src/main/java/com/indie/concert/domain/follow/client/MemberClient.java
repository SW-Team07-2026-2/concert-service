package com.indie.concert.domain.follow.client;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Member 호출 포트 (11/19 팔로우 탭) — 구현은 HttpMemberClient (내부 토큰 iss=concert · 타임아웃 3초).
 * 불통 · 5xx · 타임아웃은 전부 Unavailable.
 */
public interface MemberClient {

    /** 회원의 팔로우 전부 — 최근 팔로우순 */
    List<FollowedArtist> follows(long userId);

    /** followerCount — 그 아티스트의 지금 팔로워 수 */
    record FollowedArtist(long artistId, OffsetDateTime followedAt, long followerCount) {}

    class Unavailable extends RuntimeException {
        public Unavailable(Throwable cause) {
            super("member follows failed: " + cause.getMessage(), cause);
        }
    }
}
