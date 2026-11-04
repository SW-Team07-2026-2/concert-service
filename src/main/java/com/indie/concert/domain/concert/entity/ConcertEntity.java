package com.indie.concert.domain.concert.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * concert — (source, external_id) 가 수집 멱등 upsert 기준입니다.
 * 아티스트 이름 · 장르는 Catalog 값을 복제해 둡니다 (FR-CON-31 — 장르 필터 · 목록 표시에 Catalog 호출 없음).
 * 🔴 복제값은 공연 수집 때만 갱신됩니다 — artist.updated 구독은 범위 조정으로 뺐습니다 (07 §5 #8).
 */
@Entity
@Table(name = "concert", uniqueConstraints = @UniqueConstraint(name = "uq_concert_source", columnNames = {"source", "external_id"}))
public class ConcertEntity {

    public enum Type { SOLO, FESTIVAL }

    /** 판매 상태 — 저장하지 않고 시각으로 계산합니다 (08 §8.1 의 sale_status 를 상태 전이 배치 없이) */
    public enum SaleStatus { SCHEDULED, ON_SALE, CLOSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long artistId;

    private String artistName;

    private String artistGenre;

    private Long venueId;

    private String title;

    @Enumerated(EnumType.STRING)
    private Type type;

    private OffsetDateTime startsAt;

    /** 공연 끝 시각 — 모르면 null (입장 QR 마감은 시작 + entry.close-after-hours) */
    private OffsetDateTime endsAt;

    private Integer price;

    private OffsetDateTime bookingOpenAt;

    @Column(nullable = false)
    private Boolean queueEnabled = true;

    private String source;

    private String externalId;

    private String sourceHash;

    private OffsetDateTime updatedAt;

    protected ConcertEntity() {}

    public ConcertEntity(String source, String externalId) {
        this.source = source;
        this.externalId = externalId;
    }

    /** 값을 덮어쓰고 바뀐 게 있으면 true. queueEnabled 가 null 이면 기존 값을 둡니다 (수집기는 보통 모릅니다) */
    public boolean apply(long artistId, String artistName, String artistGenre, long venueId, String title, Type type,
                         OffsetDateTime startsAt, OffsetDateTime endsAt, Integer price, OffsetDateTime bookingOpenAt,
                         Boolean queueEnabled, String sourceHash, OffsetDateTime now) {
        boolean nextQueue = queueEnabled == null ? this.queueEnabled : queueEnabled;
        boolean changed = !Objects.equals(this.artistId, artistId) || !Objects.equals(this.artistName, artistName)
                || !Objects.equals(this.artistGenre, artistGenre) || !Objects.equals(this.venueId, venueId)
                || !Objects.equals(this.title, title) || this.type != type || !sameInstant(this.startsAt, startsAt)
                || !sameInstant(this.endsAt, endsAt) || !Objects.equals(this.price, price) || !sameInstant(this.bookingOpenAt, bookingOpenAt)
                || this.queueEnabled != nextQueue;
        this.artistId = artistId;
        this.artistName = artistName;
        this.artistGenre = artistGenre;
        this.venueId = venueId;
        this.title = title;
        this.type = type;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.price = price;
        this.bookingOpenAt = bookingOpenAt;
        this.queueEnabled = nextQueue;
        this.sourceHash = sourceHash;
        if (changed || this.updatedAt == null) this.updatedAt = now;
        return changed;
    }

    /** 공연 시작 이후 CLOSED · 오픈 시각 전(또는 미정) SCHEDULED · 그 사이 ON_SALE */
    public SaleStatus saleStatus(OffsetDateTime now) {
        if (!now.isBefore(startsAt)) return SaleStatus.CLOSED;
        if (bookingOpenAt == null || now.isBefore(bookingOpenAt)) return SaleStatus.SCHEDULED;
        return SaleStatus.ON_SALE;
    }

    /** +09:00 으로 들어온 값과 DB 에서 읽은 값의 오프셋이 달라도 같은 시각이면 같다고 봅니다 */
    private static boolean sameInstant(OffsetDateTime a, OffsetDateTime b) {
        return a == null ? b == null : b != null && a.isEqual(b);
    }

    public Long getId() { return id; }
    public Long getArtistId() { return artistId; }
    public String getArtistName() { return artistName; }
    public String getArtistGenre() { return artistGenre; }
    public Long getVenueId() { return venueId; }
    public String getTitle() { return title; }
    public Type getType() { return type; }
    public OffsetDateTime getStartsAt() { return startsAt; }
    public OffsetDateTime getEndsAt() { return endsAt; }
    public Integer getPrice() { return price; }
    public OffsetDateTime getBookingOpenAt() { return bookingOpenAt; }
    public boolean isQueueEnabled() { return queueEnabled; }
    public String getSourceHash() { return sourceHash; }
}
