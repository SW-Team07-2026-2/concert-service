package com.indie.concert.domain.concert.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** venue — 관리자(Studio)가 등록합니다. 이름이 공통 키 (같은 이름 두 번 등록 불가) · 배치는 floor ─< section ─< seat */
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

    public VenueEntity(String name, Region region, String address, Integer capacity) {
        this.name = name;
        this.region = region;
        this.address = address;
        this.capacity = capacity;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public Region getRegion() { return region; }
    public String getAddress() { return address; }
    public Integer getCapacity() { return capacity; }
}
