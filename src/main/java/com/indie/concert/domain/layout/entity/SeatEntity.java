package com.indie.concert.domain.layout.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;

/**
 * seat — 공연장 좌석의 위치만 (공연이 바뀌어도 그대로). 판매 상태 · 가격은 공연마다 concert_seat 에 있습니다.
 * x · y 는 무대 그리기 캔버스 기준 0~1, angle 은 무대 기준 기울기(도). 쓰기는 LayoutRepository 의 unnest 한 문장으로만 합니다.
 */
@Entity
@Table(name = "seat", uniqueConstraints = @UniqueConstraint(name = "uq_seat_position",
        columnNames = {"section_id", "row_no", "seat_no"}))
public class SeatEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long sectionId;

    private Integer rowNo;

    private Integer seatNo;

    @Column(precision = 5, scale = 4)
    private BigDecimal x;

    @Column(precision = 5, scale = 4)
    private BigDecimal y;

    @Column(precision = 5, scale = 1)
    private BigDecimal angle;

    protected SeatEntity() {}

    public Long getId() { return id; }
    public Long getSectionId() { return sectionId; }
    public Integer getRowNo() { return rowNo; }
    public Integer getSeatNo() { return seatNo; }
    public BigDecimal getX() { return x; }
    public BigDecimal getY() { return y; }
    public BigDecimal getAngle() { return angle; }
}
