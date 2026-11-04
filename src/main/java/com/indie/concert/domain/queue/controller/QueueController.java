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

    @PostMapping("/api/concerts/{id}/queue")
    public ApiResponse<QueueStatus> enter(@CurrentUser Long userId, @PathVariable @Positive long id) {
        return ApiResponse.onSuccess(queueService.enter(id, userId));
    }

    @GetMapping("/api/concerts/{id}/queue/status")
    public ApiResponse<QueueStatus> status(@CurrentUser Long userId, @PathVariable @Positive long id) {
        return ApiResponse.onSuccess(queueService.status(id, userId));
    }

    @DeleteMapping("/api/concerts/{id}/queue")
    public ResponseEntity<Void> leave(@CurrentUser Long userId, @PathVariable @Positive long id) {
        queueService.leave(id, userId);
        return ResponseEntity.noContent().build();
    }
}
