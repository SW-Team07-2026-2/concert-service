package com.indie.concert.domain.queue.controller;

import com.indie.concert.domain.queue.dto.response.QueueStatus;
import com.indie.concert.domain.queue.service.QueueService;
import com.indie.concert.global.auth.CurrentUser;
import com.indie.concert.global.common.response.ApiResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 공개 API N4 ~ N6 — 대기열. 모두 로그인 필요 (POST · DELETE 는 CSRF 도 — Gateway) */
@RestController
public class QueueController {

    private final QueueService queueService;

    public QueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    @PostMapping("/api/concerts/{concertId}/queue")
    public ApiResponse<QueueStatus> enter(@CurrentUser Long userId, @PathVariable @Positive long concertId) {
        return ApiResponse.onSuccess(queueService.enter(concertId, userId));
    }

    @GetMapping("/api/concerts/{concertId}/queue/status")
    public ApiResponse<QueueStatus> status(@CurrentUser Long userId, @PathVariable @Positive long concertId) {
        return ApiResponse.onSuccess(queueService.status(concertId, userId));
    }

    @DeleteMapping("/api/concerts/{concertId}/queue")
    public ResponseEntity<Void> leave(@CurrentUser Long userId, @PathVariable @Positive long concertId) {
        queueService.leave(concertId, userId);
        return ResponseEntity.noContent().build();
    }
}
