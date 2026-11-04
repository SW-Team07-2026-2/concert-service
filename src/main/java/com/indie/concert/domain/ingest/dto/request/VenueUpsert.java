package com.indie.concert.domain.ingest.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.indie.concert.domain.concert.entity.Region;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** name 이 upsert 기준 · region 은 코드표(Region) 밖이면 400 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VenueUpsert(@NotBlank @Size(max = 100) String name,
                          @NotNull Region region,
                          @Size(max = 255) String address,
                          @Positive Integer capacity) {}
