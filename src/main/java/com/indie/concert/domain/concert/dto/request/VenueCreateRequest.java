package com.indie.concert.domain.concert.dto.request;

import com.indie.concert.domain.concert.entity.Region;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 공연장 등록 — 이름이 같은 공연장은 두 번 등록할 수 없습니다 (409) */
public record VenueCreateRequest(@NotBlank @Size(max = 100) String name,
                                 @NotNull Region region,
                                 @Size(max = 255) String address,
                                 @Positive Integer capacity) {}
