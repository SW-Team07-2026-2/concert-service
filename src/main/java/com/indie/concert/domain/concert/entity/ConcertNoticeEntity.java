package com.indie.concert.domain.concert.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** concert_notice - 공연 변경 공지 . 관리자(Studio)가 쓰고 공연 상세 화면에 최신순으로 보입니다 */
@Entity
@Table(name = "concert_notice")
public class ConcertNoticeEntity {

    /** 일정(연기 포함), 장소, 출연진, 예매 일정, 취소, 기타 - 값을 바꾸면 V2 ck_concert_notice_kind 도 */
    public enum Kind { SCHEDULE, VENUE, LINEUP, BOOKING, CANCEL, ETC }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long concertId;

    @Enumerated(EnumType.STRING)
    private Kind kind;

    private String title;

    private String body;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    protected ConcertNoticeEntity() {}

    public ConcertNoticeEntity(long concertId, Kind kind, String title, String body, OffsetDateTime now) {
        this.concertId = concertId;
        this.kind = kind;
        this.title = title;
        this.body = body;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 보낸 값만 (null 은 그대로) */
    public void edit(Kind kind, String title, String body, OffsetDateTime now) {
        if (kind != null) this.kind = kind;
        if (title != null) this.title = title;
        if (body != null) this.body = body;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public Long getConcertId() { return concertId; }
    public Kind getKind() { return kind; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
