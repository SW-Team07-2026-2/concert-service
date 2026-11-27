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

/** 로그인한 프론트 -> Gateway -> Concert 대기열 진입, 상태 조회와 이탈 API. */
@RestController
public class QueueController {

    private final QueueService queueService;

    public QueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    /**
     * 공연 대기열에 진입합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param concertId 공연 ID
     */
    @PostMapping("/api/concerts/{concertId}/queue")
    public ApiResponse<QueueStatus> enter(@CurrentUser Long userId, @PathVariable @Positive long concertId) {
        return ApiResponse.onSuccess(queueService.enter(concertId, userId));
    }

    /**
     * 대기 순번이나 입장 상태를 조회합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param concertId 공연 ID
     */
    @GetMapping("/api/concerts/{concertId}/queue/status")
    public ApiResponse<QueueStatus> status(@CurrentUser Long userId, @PathVariable @Positive long concertId) {
        return ApiResponse.onSuccess(queueService.status(concertId, userId));
    }

    /**
     * 대기열에서 이탈합니다.
     *
     * @param userId 조회하거나 처리할 회원 ID
     * @param concertId 공연 ID
     */
    @DeleteMapping("/api/concerts/{concertId}/queue")
    public ResponseEntity<Void> leave(@CurrentUser Long userId, @PathVariable @Positive long concertId) {
        queueService.leave(concertId, userId);
        return ResponseEntity.noContent().build();
    }
}
