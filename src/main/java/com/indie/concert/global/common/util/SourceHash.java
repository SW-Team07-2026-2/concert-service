package com.indie.concert.global.common.util;

/** 수집 원본의 (external_id, source_hash) — I15 증분 판정용 조회 결과 */
public record SourceHash(String externalId, String sourceHash) {}
