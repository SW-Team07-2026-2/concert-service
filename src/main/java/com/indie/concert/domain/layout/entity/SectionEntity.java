package com.indie.concert.domain.layout.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

/**
 * section — 층 안의 구역. 모양이 제각각이라 꼭짓점 목록(shape)과 무대 기준 기울기(angle)로 저장합니다.
 * shape 는 [[x, y], ...] JSON (무대 그리기 캔버스 기준 0~1) — 프론트가 그대로 다각형으로 그립니다.
 * 쓰기는 LayoutRepository 의 upsert 한 문장으로만 합니다 (같은 구역을 다시 보내면 모양 · 기울기를 덮어씀).
 */
@Entity
@Table(name = "section", uniqueConstraints = @UniqueConstraint(name = "uq_section", columnNames = {"floor_id", "name"}))
public class SectionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long floorId;

    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    private String shape;

    @Column(precision = 5, scale = 1)
    private BigDecimal angle;

    protected SectionEntity() {}

    public Long getId() { return id; }
    public Long getFloorId() { return floorId; }
    public String getName() { return name; }
    public String getShape() { return shape; }
    public BigDecimal getAngle() { return angle; }
}
