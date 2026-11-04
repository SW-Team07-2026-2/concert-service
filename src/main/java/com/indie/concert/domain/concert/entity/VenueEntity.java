package com.indie.concert.domain.concert.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Objects;

/** venue — 수집 본문의 venue.name 으로 upsert 합니다 (공연장은 원문 사이트마다 식별자가 달라 이름이 공통 키) */
@Entity
@Table(name = "venue", uniqueConstraints = @UniqueConstraint(name = "uq_venue_name", columnNames = "name"))
public class VenueEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private Region region;

    private String address;

    private Integer capacity;

    protected VenueEntity() {}

    public VenueEntity(String name) {
        this.name = name;
    }

    /** address · capacity 가 null 이면 기존 값을 둡니다 (원문마다 주는 정보가 다름). 바뀐 게 있으면 true */
    public boolean apply(Region region, String address, Integer capacity) {
        String nextAddress = address == null ? this.address : address;
        Integer nextCapacity = capacity == null ? this.capacity : capacity;
        boolean changed = this.region != region || !Objects.equals(this.address, nextAddress)
                || !Objects.equals(this.capacity, nextCapacity);
        this.region = region;
        this.address = nextAddress;
        this.capacity = nextCapacity;
        return changed;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public Region getRegion() { return region; }
    public String getAddress() { return address; }
    public Integer getCapacity() { return capacity; }
}
