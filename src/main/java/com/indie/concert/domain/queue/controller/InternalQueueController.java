package com.indie.concert.domain.queue.controller;

import com.indie.concert.domain.queue.dto.response.QueueMetrics;
import com.indie.concert.domain.queue.dto.response.QueueRebuilt;
import com.indie.concert.domain.queue.service.QueueService;
import com.indie.concert.global.common.response.ApiResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 운영 내부 호출 -> Concert 대기열 복구와 메트릭 API. 평상시 복구는 대기열 조회 경로에서 자동 처리합니다. */
@RestController
public class InternalQueueController {

    private final QueueService queueService;

    public InternalQueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    /**
     * DB 스냅샷으로 Redis 대기열을 수동 복구합니다.
     *
     * @param concertId 공연 ID
     */
    @PostMapping("/internal/concerts/{concertId}/queue/rebuild")
    public ApiResponse<QueueRebuilt> rebuild(@PathVariable @Positive long concertId) {
        return ApiResponse.onSuccess(queueService.rebuildManually(concertId));
    }

    /**
     * 공연 대기열의 운영 메트릭을 반환합니다.
     *
     * @param concertId 공연 ID
     */
    @GetMapping("/internal/concerts/{concertId}/queue/metrics")
    public ApiResponse<QueueMetrics> metrics(@PathVariable @Positive long concertId) {
        return ApiResponse.onSuccess(queueService.metrics(concertId));
    }
}
