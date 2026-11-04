package com.indie.concert.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("app")
public record ConcertProperties(Internal internal, InternalToken internalToken, Payment payment, String zone,
                                Booking booking, Queue queue, Page page, Cache cache, Outbox outbox, Redis redis, Entry entry) {

    /** ingestIssuer · ingestPaths — Gateway 수집 토큰을 받는 곳 (docs/22 S1) */
    public record Internal(String expectedAudience, List<String> allowedIssuers, long clockSkewSeconds,
                           String ingestIssuer, List<String> ingestPaths) {}

    public record InternalToken(String issuer, long ttlSeconds) {}

    public record Payment(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {}

    public record Booking(long selectMinutes, long claimSeconds, long cancelDeadlineHours, long expiryIntervalMs, long lockTtlMs) {}

    public record Queue(int admitBatch, long admitIntervalMs, int maxPopsPerTick, long snapshotIntervalMs, int snapshotBatch,
                        long aliveTtlSeconds, long entryTtlMinutes, long pollAfterMs, long degradedPollAfterMs,
                        long leaderTtlMs) {}

    public record Page(int size, int maxSize) {}

    public record Cache(long seatsTtlMs) {}

    public record Outbox(long relayIntervalMs, int batchSize) {}

    public record Redis(Node queue, Node cache) {}

    public record Node(String host, int port) {}

    public record Entry(long qrTtlSeconds, long openBeforeHours, long closeAfterHours) {}
}
