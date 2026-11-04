package com.indie.concert.domain.queue.dto.response;

public record QueueMetrics(long concertId, boolean ready, long waiting, long lastSnapshotSeq, long dbWaiting) {}
