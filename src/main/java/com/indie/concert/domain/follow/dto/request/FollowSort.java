package com.indie.concert.domain.follow.dto.request;

/**
 * 팔로우 탭 정렬 (, 프론트 요구 1-2) - 없으면 RECENT_FOLLOW.
 * RECENT_FOLLOW - 최근에 팔로우한 아티스트의 공연부터
 * FOLLOWERS - 팔로워가 많은 아티스트의 공연부터
 * UPDATED - 공연이 최근에 등록, 수정된 순
 */
public enum FollowSort { RECENT_FOLLOW, FOLLOWERS, UPDATED }
