package com.indie.concert.domain.layout.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** floor — 공연장의 층 ('1F' · '2F'). 구역 배치를 저장할 때 이름으로 찾아 없으면 만듭니다 */
@Entity
@Table(name = "floor", uniqueConstraints = @UniqueConstraint(name = "uq_floor", columnNames = {"venue_id", "name"}))
public class FloorEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long venueId;

    private String name;

    protected FloorEntity() {}

    public Long getId() { return id; }
    public Long getVenueId() { return venueId; }
    public String getName() { return name; }
}
