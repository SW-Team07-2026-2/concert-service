package com.indie.concert.domain.concert.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * concert — 관리자(Studio)가 검수한 뒤에만 등록됩니다 (수집기 직접 적재 없음). 라인업은 concert_artist.
 * genre — 단독 공연(SOLO)은 그 아티스트 장르를 따르고(artist.updated 로 함께 갱신), 페스티벌은 관리자가 정합니다.
 * source · external_id — 수집 데이터로 등록할 때만 (같은 수집 공연 두 번 등록 방지 · uq_concert_source 는 부분 인덱스라 Flyway 에만).
 */
@Entity
@Table(name = "concert")
public class ConcertEntity {

    public enum Type { SOLO, FESTIVAL }

    /** 판매 상태 — 저장하지 않고 시각으로 계산합니다 (상태 전이 배치 없이) */
    public enum SaleStatus { SCHEDULED, ON_SALE, CLOSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long venueId;

    private String title;

    @Enumerated(EnumType.STRING)
    private Type type;

    private String genre;

    private OffsetDateTime startsAt;

    /** 공연 끝 시각 — 모르면 null (입장 QR 마감은 시작 + entry.close-after-hours) */
    private OffsetDateTime endsAt;

    /** 목록에 보여 줄 대표 가격 — 결제 금액은 concert_seat.price */
    private Integer price;

    private OffsetDateTime bookingOpenAt;

    @Column(nullable = false)
    private Boolean queueEnabled = true;

    /** 1인 N매 — 예매 하나에 담을 수 있는 좌석 수 */
    private Integer maxSeatsPerUser;

    private String source;

    private String externalId;

    /** 상세 · 예매 약관 · 관람 요구사항 (마크다운) — 없으면 null */
    private String description;

    private String terms;

    private String requirements;

    private String posterUrl;

    /** 상세 이미지 — 보여 줄 순서대로 */
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> imageUrls = List.of();

    private OffsetDateTime updatedAt;

    protected ConcertEntity() {}

    public ConcertEntity(long venueId, String title, Type type, String genre, OffsetDateTime startsAt, OffsetDateTime endsAt,
                         Integer price, OffsetDateTime bookingOpenAt, boolean queueEnabled, int maxSeatsPerUser,
                         String source, String externalId, OffsetDateTime now) {
        this.venueId = venueId;
        this.title = title;
        this.type = type;
        this.genre = genre;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.price = price;
        this.bookingOpenAt = bookingOpenAt;
        this.queueEnabled = queueEnabled;
        this.maxSeatsPerUser = maxSeatsPerUser;
        this.source = source;
        this.externalId = externalId;
        this.updatedAt = now;
    }

    /** 관리자 수정 — null 인 값은 그대로 둡니다. 종류(SOLO · FESTIVAL)와 수집 출처는 바꾸지 않습니다 */
    public void update(Long venueId, String title, OffsetDateTime startsAt, OffsetDateTime endsAt, Integer price,
                       OffsetDateTime bookingOpenAt, Boolean queueEnabled, Integer maxSeatsPerUser, OffsetDateTime now) {
        if (venueId != null) this.venueId = venueId;
        if (title != null) this.title = title;
        if (startsAt != null) this.startsAt = startsAt;
        if (endsAt != null) this.endsAt = endsAt;
        if (price != null) this.price = price;
        if (bookingOpenAt != null) this.bookingOpenAt = bookingOpenAt;
        if (queueEnabled != null) this.queueEnabled = queueEnabled;
        if (maxSeatsPerUser != null) this.maxSeatsPerUser = maxSeatsPerUser;
        this.updatedAt = now;
    }

    /** 상세 · 약관 · 요구사항 · 이미지 — null 인 값은 그대로 둡니다 (imageUrls 는 보내면 목록 전체 교체) */
    public void describe(String description, String terms, String requirements, String posterUrl, List<String> imageUrls,
                         OffsetDateTime now) {
        if (description != null) this.description = description;
        if (terms != null) this.terms = terms;
        if (requirements != null) this.requirements = requirements;
        if (posterUrl != null) this.posterUrl = posterUrl;
        if (imageUrls != null) this.imageUrls = List.copyOf(imageUrls);
        this.updatedAt = now;
    }

    /** 단독 공연은 아티스트 장르 · 페스티벌은 관리자 값 — 어느 쪽인지는 서비스가 정합니다 */
    public void changeGenre(String genre, OffsetDateTime now) {
        this.genre = genre;
        this.updatedAt = now;
    }

    /** 공연 시작 이후 CLOSED · 오픈 시각 전(또는 미정) SCHEDULED · 그 사이 ON_SALE */
    public SaleStatus saleStatus(OffsetDateTime now) {
        if (!now.isBefore(startsAt)) return SaleStatus.CLOSED;
        if (bookingOpenAt == null || now.isBefore(bookingOpenAt)) return SaleStatus.SCHEDULED;
        return SaleStatus.ON_SALE;
    }

    public Long getId() { return id; }
    public Long getVenueId() { return venueId; }
    public String getTitle() { return title; }
    public Type getType() { return type; }
    public String getGenre() { return genre; }
    public OffsetDateTime getStartsAt() { return startsAt; }
    public OffsetDateTime getEndsAt() { return endsAt; }
    public Integer getPrice() { return price; }
    public OffsetDateTime getBookingOpenAt() { return bookingOpenAt; }
    public boolean isQueueEnabled() { return queueEnabled; }
    public int getMaxSeatsPerUser() { return maxSeatsPerUser; }
    public String getSource() { return source; }
    public String getExternalId() { return externalId; }
    public String getDescription() { return description; }
    public String getTerms() { return terms; }
    public String getRequirements() { return requirements; }
    public String getPosterUrl() { return posterUrl; }
    public List<String> getImageUrls() { return imageUrls; }
}
