package com.indie.concert.domain.concert.entity;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 판매 상태는 저장하지 않고 시각으로 계산합니다 (08 §8.1) · 관리자 수정은 보낸 값만 바꿉니다 */
class ConcertEntityTest {

    private static final OffsetDateTime STARTS = OffsetDateTime.parse("2026-11-20T19:00:00+09:00");
    private static final OffsetDateTime OPENS = OffsetDateTime.parse("2026-11-01T20:00:00+09:00");

    private static ConcertEntity concert(OffsetDateTime opens) {
        return new ConcertEntity(1L, "단독공연", ConcertEntity.Type.SOLO, "INDIE_ROCK", STARTS, null, 55000, opens, true, 4,
                null, null, STARTS.minusDays(30));
    }

    @Test
    void scheduledBeforeBookingOpens() {
        assertThat(concert(OPENS).saleStatus(OPENS.minusSeconds(1))).isEqualTo(ConcertEntity.SaleStatus.SCHEDULED);
    }

    @Test
    void scheduledWhenOpenTimeUnknown() {
        assertThat(concert(null).saleStatus(OPENS.plusDays(1))).isEqualTo(ConcertEntity.SaleStatus.SCHEDULED);
    }

    @Test
    void onSaleFromOpenUntilStart() {
        assertThat(concert(OPENS).saleStatus(OPENS)).isEqualTo(ConcertEntity.SaleStatus.ON_SALE);
        assertThat(concert(OPENS).saleStatus(STARTS.minusSeconds(1))).isEqualTo(ConcertEntity.SaleStatus.ON_SALE);
    }

    @Test
    void closedOnceStarted() {
        assertThat(concert(OPENS).saleStatus(STARTS)).isEqualTo(ConcertEntity.SaleStatus.CLOSED);
    }

    /** null 은 그대로 — 대기열 · 1인 매수처럼 보낸 값만 바뀝니다 */
    @Test
    void updateChangesOnlyGivenValues() {
        ConcertEntity c = concert(OPENS);
        c.update(null, null, null, STARTS.plusHours(2), null, null, false, 2, STARTS);

        assertThat(c.getEndsAt()).isEqualTo(STARTS.plusHours(2));
        assertThat(c.isQueueEnabled()).isFalse();
        assertThat(c.getMaxSeatsPerUser()).isEqualTo(2);
        assertThat(c.getTitle()).isEqualTo("단독공연");
        assertThat(c.getBookingOpenAt()).isEqualTo(OPENS);
        assertThat(c.getPrice()).isEqualTo(55000);
        assertThat(c.getGenre()).isEqualTo("INDIE_ROCK");
    }
}
