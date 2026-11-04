package com.indie.concert.domain.ingest.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ItemResult(String externalId, Long id, Result result, String error) {

    public enum Result { CREATED, UPDATED, UNCHANGED, FAILED }

    public static ItemResult failed(String externalId, String error) {
        return new ItemResult(externalId, null, Result.FAILED, error);
    }
}
