package com.indie.concert.global.common.util;

/**
 * 목록 정렬 (팔로우 탭) - 없으면 공연일 가까운 순(기존).
 * ARTIST_ORDER - 넘긴 아티스트 ID 순서대로 (라인업 중 가장 앞 순서의 아티스트 기준, 최근 팔로우순, 팔로워순은 프론트가 순서를 정해 넘깁니다)
 * UPDATED - 최근 수정순 (updated_at 내림차순)
 */
public enum ListSort { ARTIST_ORDER, UPDATED }
