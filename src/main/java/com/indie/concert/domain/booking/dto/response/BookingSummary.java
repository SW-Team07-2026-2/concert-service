package com.indie.concert.domain.booking.dto.response;

import java.time.OffsetDateTime;

/** startsAt = 예매한 회차의 시작 , 입장 필드 5개는 EntryInfo 와 같음 */
public record BookingSummary(long bookingId, String status, int totalPrice, OffsetDateTime createdAt,
                             long concertId, String concertTitle, OffsetDateTime startsAt, long sessionId,
                             String entryStatus, OffsetDateTime entryQrOpensAt, OffsetDateTime entryQrClosesAt,
                             OffsetDateTime checkedInAt, OffsetDateTime serverTime) {}
