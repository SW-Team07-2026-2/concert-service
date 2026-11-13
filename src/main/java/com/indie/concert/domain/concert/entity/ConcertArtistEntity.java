package com.indie.concert.domain.concert.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.io.Serializable;
import java.util.Objects;

/**
 * concert_artist — 라인업 (공연 ─< concert_artist >─ 아티스트). billingOrder 1 = 헤드라이너.
 * 단독 공연(SOLO)은 1명, 페스티벌은 1명 이상. 라인업 수정은 전체 교체입니다 (ConcertAdminService.replaceLineup).
 */
@Entity
@Table(name = "concert_artist", uniqueConstraints = @UniqueConstraint(name = "uq_concert_artist_order",
        columnNames = {"concert_id", "billing_order"}))
@IdClass(ConcertArtistEntity.Key.class)
public class ConcertArtistEntity {

    @Id
    private Long concertId;

    @Id
    private Long artistId;

    private Integer billingOrder;

    protected ConcertArtistEntity() {}

    public Long getConcertId() { return concertId; }
    public Long getArtistId() { return artistId; }
    public Integer getBillingOrder() { return billingOrder; }

    public static class Key implements Serializable {
        private Long concertId;
        private Long artistId;

        public Key() {}

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(concertId, k.concertId) && Objects.equals(artistId, k.artistId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(concertId, artistId);
        }
    }
}
