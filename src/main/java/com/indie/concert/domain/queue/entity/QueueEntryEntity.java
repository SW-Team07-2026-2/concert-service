package com.indie.concert.domain.queue.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.ColumnDefault;

import java.time.OffsetDateTime;

/**
 * queue_entry (16 §3) — 대기열의 DB 스냅샷. 쓰기는 전부 QueueEntryRepository 의 배치 upsert 입니다 (JPA 로 한 건씩 넣지 않음).
 * 엔티티는 로컬 ddl update 와 운영 validate 를 위해 둡니다.
 */
@Entity
@Table(name = "queue_entry", uniqueConstraints = @UniqueConstraint(name = "uq_queue_user", columnNames = {"concert_id", "user_id"}))
public class QueueEntryEntity {

    public enum Status { WAITING, ADMITTED, LEFT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long concertId;

    private Long userId;

    private Long seq;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(nullable = false, insertable = false, updatable = false)
    @ColumnDefault("now()")                              // 로컬 ddl update 용 — 스냅샷 INSERT 가 entered_at 을 넣지 않습니다
    private OffsetDateTime enteredAt;

    private OffsetDateTime admittedAt;

    private String entryToken;                           // ADMITTED 일 때만 — Redis 복구 원천

    protected QueueEntryEntity() {}
}
