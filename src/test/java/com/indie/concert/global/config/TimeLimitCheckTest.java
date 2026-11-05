package com.indie.concert.global.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeLimitCheckTest {

    /** application.yml 값 */
    static ConcertProperties props(long holdMinutes, long holdGraceSeconds, long cancelDeadlineHours, long openBeforeHours,
                                   long admitIntervalMs, long leaderTtlMs, long aliveTtlSeconds, long degradedPollAfterMs) {
        return new ConcertProperties(null, null, null, "Asia/Seoul",
                new ConcertProperties.Booking(holdMinutes, holdGraceSeconds, 60, cancelDeadlineHours, 30000),
                new ConcertProperties.Queue(20, admitIntervalMs, 200, 5000, 1000, aliveTtlSeconds, 10, 2000, degradedPollAfterMs, leaderTtlMs),
                null, null, null, null,
                new ConcertProperties.Entry(30, openBeforeHours, 6));
    }

    @Test
    void currentSettingsPass() {
        assertThatCode(() -> TimeLimitCheck.check(props(8, 60, 24, 3, 1000, 5000, 15, 3000))).doesNotThrowAnyException();
    }

    @Test
    void holdReachingTossDeadlineFails() {
        assertThatThrownBy(() -> TimeLimitCheck.check(props(9, 60, 24, 3, 1000, 5000, 15, 3000)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("토스");
    }

    @Test
    void qrOpeningBeforeCancelDeadlineFails() {
        assertThatThrownBy(() -> TimeLimitCheck.check(props(8, 60, 24, 24, 1000, 5000, 15, 3000)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("open-before-hours");
    }

    @Test
    void leaderLockShorterThanAdmitIntervalFails() {
        assertThatThrownBy(() -> TimeLimitCheck.check(props(8, 60, 24, 3, 1000, 1000, 15, 3000)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("leader-ttl-ms");
    }

    @Test
    void aliveTtlShorterThanDegradedPollFails() {
        assertThatThrownBy(() -> TimeLimitCheck.check(props(8, 60, 24, 3, 1000, 5000, 3, 3000)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("alive-ttl-seconds");
    }
}
