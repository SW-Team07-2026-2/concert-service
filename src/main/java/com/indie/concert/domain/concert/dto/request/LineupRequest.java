package com.indie.concert.domain.concert.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 라인업 전체 교체 — 순서가 라인업 순서 (첫 번째 = 헤드라이너). 라인업이 차례로 공개되면 그때마다 전체 목록을 보냅니다 */
public record LineupRequest(@NotEmpty @Size(max = 100) List<@NotNull @Positive Long> artistIds) {}
