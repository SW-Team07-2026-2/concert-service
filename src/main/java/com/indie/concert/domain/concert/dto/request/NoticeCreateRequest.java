package com.indie.concert.domain.concert.dto.request;

import com.indie.concert.domain.concert.entity.ConcertNoticeEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 공연 변경 공지 쓰기 - 저장되면 라인업 팔로워, 확정 예매자에게 알림 (concert.notice_posted) */
public record NoticeCreateRequest(@NotNull ConcertNoticeEntity.Kind kind,
                                  @NotBlank @Size(max = 200) String title,
                                  @NotBlank @Size(max = 20000) String body) {}
