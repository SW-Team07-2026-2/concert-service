package com.indie.concert.domain.stage.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/** stage_drawing — GPU 서버가 보낸 무대 그리기 JSON 원본 (가안). 형식이 정해지면 정식 컬럼으로 매핑합니다 */
@Entity
@Table(name = "stage_drawing")
public class StageDrawingEntity {

    @Id
    private Long venueId;

    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    private OffsetDateTime receivedAt;

    protected StageDrawingEntity() {}

    public StageDrawingEntity(Long venueId) {
        this.venueId = venueId;
    }

    /** 공연장당 최신 1건만 둡니다 */
    public void replace(String payload, OffsetDateTime receivedAt) {
        this.payload = payload;
        this.receivedAt = receivedAt;
    }

    public Long getVenueId() { return venueId; }
    public String getPayload() { return payload; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
}
