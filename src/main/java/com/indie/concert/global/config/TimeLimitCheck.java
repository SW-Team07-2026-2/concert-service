package com.indie.concert.global.config;

import org.springframework.stereotype.Component;

/**
 * ✏️ 11/04 75-2 — 제한시간끼리의 관계가 설정에서 깨지면 기동 실패 (docs/30 §2).
 * 다른 서비스와의 관계(Payment ttl-minutes · Gateway 타임아웃)는 여기서 볼 수 없어 docs/30 에만 있습니다.
 */
@Component
public class TimeLimitCheck {

    static final long TOSS_APPROVE_SECONDS = 600;   // 토스 — 결제 인증 뒤 10분 안에 승인해야 함

    public TimeLimitCheck(ConcertProperties props) {
        check(props);
    }

    static void check(ConcertProperties props) {
        ConcertProperties.Booking booking = props.booking();
        ConcertProperties.Queue queue = props.queue();

        long holdSeconds = booking.holdMinutes() * 60 + booking.holdGraceSeconds();
        require(holdSeconds < TOSS_APPROVE_SECONDS,
                "선점 서버 마감(hold-minutes×60 + hold-grace-seconds = %d초)은 토스 승인 시한 %d초보다 짧아야 합니다",
                holdSeconds, TOSS_APPROVE_SECONDS);
        require(queue.entryTtlMinutes() <= booking.holdMinutes(),
                "입장 토큰(entry-ttl-minutes = %d)은 화면 시계(hold-minutes = %d)보다 길 수 없습니다 — 입장부터 결제하기까지 시계가 하나입니다",
                queue.entryTtlMinutes(), booking.holdMinutes());
        require(props.entry().openBeforeHours() < booking.cancelDeadlineHours(),
                "입장 QR 발급(open-before-hours = %d)은 취소 마감(cancel-deadline-hours = %d)보다 늦어야 합니다 — 입장 뒤 환불 방지",
                props.entry().openBeforeHours(), booking.cancelDeadlineHours());
        require(queue.leaderTtlMs() > queue.admitIntervalMs(),
                "입장 처리기 리더 락(leader-ttl-ms = %d)은 입장 주기(admit-interval-ms = %d)보다 길어야 합니다",
                queue.leaderTtlMs(), queue.admitIntervalMs());
        long slowestPollMs = Math.max(queue.pollAfterMs(), queue.degradedPollAfterMs());
        require(queue.aliveTtlSeconds() * 1000 > slowestPollMs,
                "대기 생존 TTL(alive-ttl-seconds = %d)은 폴링 간격(최대 %dms)보다 길어야 합니다 — 기다리는 사람이 이탈 처리됨",
                queue.aliveTtlSeconds(), slowestPollMs);
    }

    private static void require(boolean ok, String message, Object... args) {
        if (!ok) throw new IllegalStateException(message.formatted(args));
    }
}
