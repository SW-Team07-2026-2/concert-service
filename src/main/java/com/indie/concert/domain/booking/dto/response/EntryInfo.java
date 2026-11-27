package com.indie.concert.domain.booking.dto.response;

import java.time.OffsetDateTime;

/**
 * 티켓 화면의 입장 정보 - 예매 목록, 상세 응답에 펼쳐 넣는 값.
 * entryStatus 는 항상 값이 있습니다 (EntryWindow.Status), serverTime 은 응답을 만든 서버 시각 (남은 시간 계산용).
 */
public record EntryInfo(String entryStatus, OffsetDateTime entryQrOpensAt, OffsetDateTime entryQrClosesAt,
                        OffsetDateTime checkedInAt, OffsetDateTime serverTime) {}
