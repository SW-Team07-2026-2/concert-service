package com.indie.concert.domain.concert.dto.request;

import com.indie.concert.domain.concert.entity.ConcertNoticeEntity;
import jakarta.validation.constraints.Size;

/** 공연 변경 공지 고치기 - 보낸 값만 (null 은 그대로), 알림은 다시 보내지 않습니다 */
public record NoticeUpdateRequest(ConcertNoticeEntity.Kind kind,
                                  @Size(min = 1, max = 200) String title,
                                  @Size(min = 1, max = 20000) String body) {}
