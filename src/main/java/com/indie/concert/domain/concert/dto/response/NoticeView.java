package com.indie.concert.domain.concert.dto.response;

import com.indie.concert.domain.concert.entity.ConcertNoticeEntity;

import java.time.OffsetDateTime;

public record NoticeView(long id, long concertId, ConcertNoticeEntity.Kind kind, String title, String body,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    public static NoticeView of(ConcertNoticeEntity n) {
        return new NoticeView(n.getId(), n.getConcertId(), n.getKind(), n.getTitle(), n.getBody(), n.getCreatedAt(), n.getUpdatedAt());
    }
}
