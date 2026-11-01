package com.indie.concert.global.common.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indie.concert.domain.queue.exception.QueueErrorCode;
import com.indie.concert.global.common.exception.CommonErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void successWrapsBodyInResult() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(ApiResponse.onSuccess(Map.of("id", 7))));
        assertThat(json.fieldNames()).toIterable().containsExactly("isSuccess", "code", "message", "result");
        assertThat(json.get("isSuccess").asBoolean()).isTrue();
        assertThat(json.get("code").asText()).isEqualTo("OK");
        assertThat(json.get("result").get("id").asInt()).isEqualTo(7);
    }

    @Test
    void failureCarriesExtraValuesInResult() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(
                ApiResponse.onFailure(QueueErrorCode.ALREADY_IN_QUEUE, "이미 대기 중입니다", Map.of("rank", 1532))));
        assertThat(json.get("isSuccess").asBoolean()).isFalse();
        assertThat(json.get("code").asText()).isEqualTo("ALREADY_IN_QUEUE");
        assertThat(json.get("result").get("rank").asInt()).isEqualTo(1532);
    }

    @Test
    void plainFailureHasNoResult() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(
                ApiResponse.onFailure(CommonErrorCode.NOT_FOUND, "찾을 수 없습니다", null)));
        assertThat(json.fieldNames()).toIterable().containsExactly("isSuccess", "code", "message");
    }
}
