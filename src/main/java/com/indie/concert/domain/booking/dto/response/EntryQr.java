package com.indie.concert.domain.booking.dto.response;

import java.time.OffsetDateTime;

/** N17 — token 을 그대로 QR 로 그립니다. expiresAt(발급 + 30초)이 지나면 검표가 거절하므로 앱은 20초마다 다시 받습니다 */
public record EntryQr(String token, OffsetDateTime expiresAt) {}
