package com.indie.concert.domain.booking.dto.response;

import java.time.OffsetDateTime;

/**
 * N17 — token 을 그대로 QR 로 그립니다. expiresAt = 발급 + entry.qr-ttl-seconds (입장 마감을 넘지 않음) — 지나면 검표가 거절합니다.
 * 앱은 min(expiresAt − 10초, 지금 + 20초) 에 다시 받습니다 (11/27)
 */
public record EntryQr(String token, OffsetDateTime expiresAt) {}
