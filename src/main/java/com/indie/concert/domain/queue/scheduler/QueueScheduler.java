package com.indie.concert.domain.queue.scheduler;

import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.queue.service.QueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 대기열 주기 작업 트리거 — 입장 처리 1초 · 스냅샷 5초 (로직은 QueueService).
 * 모든 replica 에서 돌지만 공연마다 Redis 락으로 하나만 실제로 일합니다. 한 공연의 실패가 다른 공연을 막지 않게 따로 잡습니다.
 */
@Component
public class QueueScheduler {

    private static final Logger log = LoggerFactory.getLogger(QueueScheduler.class);

    private final QueueService queueService;

    public QueueScheduler(QueueService queueService) {
        this.queueService = queueService;
    }

    @Scheduled(fixedDelayString = "${app.queue.admit-interval-ms}")
    public void admit() {
        forEachActive(queueService::admit);
    }

    @Scheduled(fixedDelayString = "${app.queue.snapshot-interval-ms}")
    public void snapshot() {
        forEachActive(queueService::snapshot);
    }

    private void forEachActive(Consumer<ConcertEntity> work) {
        for (ConcertEntity concert : queueService.activeConcerts()) {
            try {
                work.accept(concert);
            } catch (RuntimeException e) {
                log.warn("queue_tick_failed concertId={} error={}", concert.getId(), e.toString());
            }
        }
    }
}
