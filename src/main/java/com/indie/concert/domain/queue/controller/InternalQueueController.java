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

/**
 * 내부 I8 · I9 — 대기열 운영 (X-Internal-Token · Gateway 라우트 없음 → 외부에서는 404).
 * I8 은 자동 복구가 실패했을 때의 수동 트리거입니다 — 평상시엔 조회 · 입장 처리 경로에서 자동으로 복구됩니다.
 */
@RestController
public class InternalQueueController {

    private final QueueService queueService;

    public InternalQueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    @PostMapping("/internal/concerts/{id}/queue/rebuild")
    public ApiResponse<QueueRebuilt> rebuild(@PathVariable @Positive long id) {
        return ApiResponse.onSuccess(queueService.rebuildManually(id));
    }

    @GetMapping("/internal/concerts/{id}/queue/metrics")
    public ApiResponse<QueueMetrics> metrics(@PathVariable @Positive long id) {
        return ApiResponse.onSuccess(queueService.metrics(id));
    }
}
