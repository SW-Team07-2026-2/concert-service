package com.indie.concert.domain.concert.entity;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** 판매 상태는 저장하지 않고 시각으로 계산합니다 (08 §8.1) · 같은 시각을 다른 오프셋으로 다시 넣어도 "변경"이 아닙니다 */
class ConcertEntityTest {

    private static final OffsetDateTime STARTS = OffsetDateTime.parse("2026-11-20T19:00:00+09:00");
    private static final OffsetDateTime OPENS = OffsetDateTime.parse("2026-11-01T20:00:00+09:00");

    private static ConcertEntity concert(OffsetDateTime opens) {
        ConcertEntity c = new ConcertEntity("seed", "c-1");
        c.apply(7L, "새소년", "INDIE", 1L, "단독공연", ConcertEntity.Type.SOLO, STARTS, null, 55000, opens, null, "h1", STARTS.minusDays(30));
        return c;
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

    @Test
    void queueIsOnByDefaultAndKeptWhenNotGiven() {
        ConcertEntity c = concert(OPENS);
        assertThat(c.isQueueEnabled()).isTrue();
        c.apply(7L, "새소년", "INDIE", 1L, "단독공연", ConcertEntity.Type.SOLO, STARTS, null, 55000, OPENS, false, "h2", STARTS);
        assertThat(c.isQueueEnabled()).isFalse();
        c.apply(7L, "새소년", "INDIE", 1L, "단독공연", ConcertEntity.Type.SOLO, STARTS, null, 55000, OPENS, null, "h3", STARTS);
        assertThat(c.isQueueEnabled()).isFalse();
    }

    @Test
    void sameInstantInAnotherOffsetIsNotAChange() {
        ConcertEntity c = concert(OPENS);
        boolean changed = c.apply(7L, "새소년", "INDIE", 1L, "단독공연", ConcertEntity.Type.SOLO,
                STARTS.withOffsetSameInstant(ZoneOffset.UTC), null, 55000, OPENS.withOffsetSameInstant(ZoneOffset.UTC), null, "h2", STARTS);
        assertThat(changed).isFalse();
    }

    /** 끝 시각은 원문 값으로 덮어씁니다 — 생기거나 바뀌면 변경 */
    @Test
    void endsAtIsStoredAndCountsAsChange() {
        ConcertEntity c = concert(OPENS);
        boolean changed = c.apply(7L, "새소년", "INDIE", 1L, "단독공연", ConcertEntity.Type.SOLO,
                STARTS, STARTS.plusHours(2), 55000, OPENS, null, "h2", STARTS);
        assertThat(changed).isTrue();
        assertThat(c.getEndsAt()).isEqualTo(STARTS.plusHours(2));
    }
}
