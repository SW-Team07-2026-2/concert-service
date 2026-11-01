package com.indie.concert.domain.ingest.controller;

import com.indie.concert.domain.ingest.dto.request.ConcertBatch;
import com.indie.concert.domain.ingest.dto.request.DiffRequest;
import com.indie.concert.domain.ingest.dto.response.BatchResult;
import com.indie.concert.domain.ingest.dto.response.DiffResult;
import com.indie.concert.domain.ingest.service.IngestService;
import com.indie.concert.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 I7 · I15 — 수집 CronJob · 시드 스크립트 전용 (X-Internal-Token · Gateway 라우트 없음 → 외부에서는 404).
 * 🔴 X-User-Id 를 보지 않습니다. 운영자 입력(FR-ING-07)도 이 경로로 넣어야 concert.created 가 함께 나갑니다.
 */
@RestController
public class InternalIngestController {

    private final IngestService ingestService;

    public InternalIngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/internal/concerts")
    public ApiResponse<BatchResult> upsert(@Valid @RequestBody ConcertBatch batch) {
        return ApiResponse.onSuccess(new BatchResult(ingestService.upsertConcerts(batch.items())));
    }

    @PostMapping("/internal/concerts/ingest/diff")
    public ApiResponse<DiffResult> diff(@Valid @RequestBody DiffRequest request) {
        return ApiResponse.onSuccess(ingestService.diff(request));
    }
}
