package com.indie.concert.domain.concert.dto.response;

import java.time.OffsetDateTime;

/**
 * 회차 (11/27) — saleStatus 는 회차마다: 이 회차 시작 이후 CLOSED · 예매 오픈(공연 하나) 전 SCHEDULED · 그 사이 ON_SALE.
 * 좌석 화면 · 예매는 이 id 로 (GET /api/concerts/{concertId}/seats?sessionId=)
 */
public record SessionView(long id, OffsetDateTime startsAt, String saleStatus) {

    public SessionView withSaleStatus(String saleStatus) {
        return new SessionView(id, startsAt, saleStatus);
    }
}
