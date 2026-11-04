package com.indie.concert.domain.queue.entity;

/** 대기열 한 칸 — seq 는 Redis score 와 같은 값 (INCR queue:seq:{id} · 단조 증가) */
public record QueueMember(long userId, long seq) {}
