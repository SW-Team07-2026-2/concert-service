package com.indie.concert.domain.queue.entity;

/** 입장 처리된 한 칸 — 입장 토큰도 DB 에 남겨 대기열 Redis 가 비워져도 되살립니다 (10/24) */
public record AdmittedMember(long userId, long seq, String entryToken) {}
