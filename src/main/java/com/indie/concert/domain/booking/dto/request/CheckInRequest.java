package com.indie.concert.domain.booking.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 검표 — token 은 찍은 QR 값 그대로 · checkerId 는 Studio 가 관리자 AT 에서 꺼낸 검표자 계정 id */
public record CheckInRequest(@NotBlank @Size(max = 1000) String token, @NotNull @Positive Long checkerId) {}
