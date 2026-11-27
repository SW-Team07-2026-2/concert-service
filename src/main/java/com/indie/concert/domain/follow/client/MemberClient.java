package com.indie.concert.domain.follow.client;

import java.time.OffsetDateTime;
import java.util.List;

/** Concert -> Member 팔로우 목록 조회 포트. 구현은 HttpMemberClient입니다. */
public interface MemberClient {

    /**
     * 회원의 팔로우 전부 - 최근 팔로우순
     *
     * @param userId 조회하거나 처리할 회원 ID
     */
    List<FollowedArtist> follows(long userId);

    /** followerCount - 그 아티스트의 지금 팔로워 수 */
    record FollowedArtist(long artistId, OffsetDateTime followedAt, long followerCount) {}

    class Unavailable extends RuntimeException {
        public Unavailable(Throwable cause) {
            super("member follows failed: " + cause.getMessage(), cause);
        }
    }
}
