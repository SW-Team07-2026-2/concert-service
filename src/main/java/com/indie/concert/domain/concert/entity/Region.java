package com.indie.concert.domain.concert.entity;

/**
 * 지역 코드표 — venue.region 과 N1 · N14 의 region 필터가 같은 값을 씁니다 (15 §4 — "서울"/"서울시" 가 섞이지 않게).
 * 수집 본문의 region 이 이 목록에 없으면 적재 단계에서 400 입니다.
 */
public enum Region {
    SEOUL, BUSAN, DAEGU, INCHEON, GWANGJU, DAEJEON, ULSAN, SEJONG,
    GYEONGGI, GANGWON, CHUNGBUK, CHUNGNAM, JEONBUK, JEONNAM, GYEONGBUK, GYEONGNAM, JEJU
}
