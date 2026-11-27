package com.indie.concert.domain.concert.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * concert_session - 회차 . 공연 하나에 날짜, 시각이 다른 회차가 여러 개 있을 수 있습니다.
 * 좌석 재고(concert_seat), 예매(booking), 입장 QR 시간, 취소 마감은 회차 기준이고, 공연 정보, 대기열, 예매 오픈, 좌석 가격은 공연 기준입니다.
 * 공연의 starts_at, last_starts_at 은 첫, 마지막 회차 시작을 옮겨 둔 값입니다 (회차를 바꾸는 곳은 ConcertAdminService 하나).
 */
@Entity
@Table(name = "concert_session")
public class ConcertSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long concertId;

    private OffsetDateTime startsAt;

    protected ConcertSessionEntity() {}

    public ConcertSessionEntity(long concertId, OffsetDateTime startsAt) {
        this.concertId = concertId;
        this.startsAt = startsAt;
    }

    /** 일정 변경 - 예매가 있어도 바꿀 수 있습니다 (회원에게는 공연 변경 공지로 알림) */
    public void reschedule(OffsetDateTime startsAt) {
        this.startsAt = startsAt;
    }

    public Long getId() { return id; }
    public Long getConcertId() { return concertId; }
    public OffsetDateTime getStartsAt() { return startsAt; }
}
