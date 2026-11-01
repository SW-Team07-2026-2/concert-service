package com.indie.concert.domain.stage.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.indie.concert.domain.stage.dto.response.StageDrawingSavedResponse;
import com.indie.concert.domain.stage.service.StageDrawingService;
import com.indie.concert.global.common.exception.CommonErrorCode;
import com.indie.concert.global.common.exception.GeneralException;
import com.indie.concert.global.common.response.ApiResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 — 무대 그리기 JSON 저장 (가안 · 10/24). 15 I16 ~ I18(작업 등록 · GPU Pull · 결과 제출) 대신 공연장 단위로 받는 한 개만 둡니다.
 * GPU 서버 담당이 형식을 정하기 전이라 JSON 객체 · 배열이면 내용을 보지 않고 저장합니다. 같은 공연장에 다시 보내면 덮어씁니다.
 * X-Internal-Token 필수 · snake_case. GPU 서버(클러스터 밖)가 어떤 토큰 · 경로로 들어올지는 결정 대기 (Gateway §18-1).
 */
@RestController
public class InternalStageController {

    private final StageDrawingService stageDrawingService;

    public InternalStageController(StageDrawingService stageDrawingService) {
        this.stageDrawingService = stageDrawingService;
    }

    @PutMapping("/internal/concerts/venues/{venueId}/stage-drawing")
    public ApiResponse<StageDrawingSavedResponse> save(@PathVariable @Positive long venueId, @RequestBody JsonNode body) {
        if (!body.isContainerNode()) throw new GeneralException(CommonErrorCode.INVALID_REQUEST, "JSON 객체나 배열이어야 합니다");
        return ApiResponse.onSuccess(new StageDrawingSavedResponse(venueId, stageDrawingService.save(venueId, body.toString())));
    }
}
