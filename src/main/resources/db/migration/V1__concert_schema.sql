-- concert 스키마 — ERD v6 기준 + 판매 컬럼(08 §8.1) + 수집 멱등키(source · external_id · source_hash) + 좌석 점유 컬럼(08 §8.3)
-- + queue_entry (16 §3). 무대 배치(venue.stage_* · stage_job)는 Studio 착수 때 V2 로 추가합니다.
-- 스키마 간 FK 없음 — artist_id 는 catalog.artist, user_id · held_by 는 member.users 를 가리키지만 FK 를 걸지 않습니다

CREATE TABLE venue (
    id       BIGSERIAL PRIMARY KEY,
    name     VARCHAR(100) NOT NULL,
    region   VARCHAR(16)  NOT NULL,                      -- SEOUL · GYEONGGI … (Region 코드표 — N1 · N14 필터가 같은 값을 씁니다)
    address  VARCHAR(255),
    capacity INTEGER,
    CONSTRAINT uq_venue_name UNIQUE (name)               -- 수집 본문의 venue.name 으로 upsert
);
CREATE INDEX idx_venue_region ON venue(region);

CREATE TABLE concert (
    id              BIGSERIAL PRIMARY KEY,
    artist_id       BIGINT       NOT NULL,               -- catalog.artist (FK 없음)
    artist_name     VARCHAR(100) NOT NULL,               -- ★ 복제 — 장르 필터의 전제 (FR-CON-31)
    artist_genre    VARCHAR(50),
    venue_id        BIGINT       NOT NULL REFERENCES venue(id),
    title           VARCHAR(200) NOT NULL,
    type            VARCHAR(16)  NOT NULL,               -- SOLO | FESTIVAL
    starts_at       TIMESTAMPTZ  NOT NULL,
    price           INTEGER CHECK (price >= 0),          -- 표시용 대표 가격 (원문에 없으면 NULL) · 결제 금액은 seat.price
    booking_open_at TIMESTAMPTZ,                         -- 예매 오픈 시각 · NULL = 미정 (판매 상태는 이 값과 starts_at 으로 계산)
    queue_enabled   BOOLEAN      NOT NULL DEFAULT TRUE,  -- 대기열 사용 — 부하 테스트 A/B 스위치 (FR-CON-26)
    source          VARCHAR(32)  NOT NULL,
    external_id     VARCHAR(128) NOT NULL,
    source_hash     VARCHAR(128),
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_concert_source UNIQUE (source, external_id)   -- ★ 멱등 upsert 기준
);
CREATE INDEX idx_concert_starts ON concert(starts_at);
CREATE INDEX idx_concert_artist ON concert(artist_id, starts_at);
CREATE INDEX idx_concert_venue ON concert(venue_id, starts_at);

CREATE TABLE booking (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL,                    -- member.users (FK 없음)
    concert_id  BIGINT      NOT NULL REFERENCES concert(id),
    status      VARCHAR(16) NOT NULL,                    -- PENDING | CONFIRMED | CANCELLED | EXPIRED
    total_price INTEGER     NOT NULL,
    payment_key VARCHAR(64),                             -- Payment prepare 응답 (NULL = 준비 전)
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_booking_user ON booking(user_id, id DESC);
-- ★ 1인 1예매의 최종 방어 (FR-CON-23) — 사용자 락이 밀려나도 진행 중 · 확정 예매는 공연당 1건
CREATE UNIQUE INDEX uq_booking_active ON booking(concert_id, user_id) WHERE status IN ('PENDING', 'CONFIRMED');

CREATE TABLE seat (
    id              BIGSERIAL PRIMARY KEY,
    concert_id      BIGINT      NOT NULL REFERENCES concert(id),
    section         VARCHAR(16) NOT NULL,
    row_no          VARCHAR(8)  NOT NULL,
    seat_no         INTEGER     NOT NULL,
    price           INTEGER     NOT NULL CHECK (price > 0),
    status          VARCHAR(16) NOT NULL,                -- AVAILABLE | HELD | SOLD
    held_by         BIGINT,                              -- member.users (FK 없음)
    hold_expires_at TIMESTAMPTZ,
    booking_id      BIGINT REFERENCES booking(id),       -- ★ 결제 중 · 판매된 예매. 있으면 점유가 만료돼도 다른 사람이 가져가지 못합니다
    CONSTRAINT uq_seat_position UNIQUE (concert_id, section, row_no, seat_no)
);
CREATE INDEX idx_seat_holder ON seat(concert_id, held_by) WHERE status = 'HELD';
CREATE INDEX idx_seat_hold_expiry ON seat(hold_expires_at) WHERE status = 'HELD';   -- 30초 정리 · 만료 예매 스캔
CREATE INDEX idx_seat_booking ON seat(booking_id);

CREATE TABLE booking_seat (                              -- 예매 당시 좌석 (seat.booking_id 는 취소되면 비워지므로 이력은 여기)
    booking_id BIGINT NOT NULL REFERENCES booking(id),
    seat_id    BIGINT NOT NULL REFERENCES seat(id),
    PRIMARY KEY (booking_id, seat_id)
);

-- ★ 대기열 스냅샷 (16 §3) — Redis 가 진실, 이 테이블은 복구 원천 · 측정 데이터 (5초 증분)
CREATE TABLE queue_entry (
    id          BIGSERIAL   PRIMARY KEY,
    concert_id  BIGINT      NOT NULL REFERENCES concert(id),
    user_id     BIGINT      NOT NULL,                    -- member.users (FK 없음)
    seq         BIGINT      NOT NULL,                    -- ★ Redis score 와 동일 (순서의 진실)
    status      VARCHAR(12) NOT NULL,                    -- WAITING | ADMITTED | LEFT
    entered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),      -- 스냅샷 시각 (실제 진입보다 최대 5초 늦음)
    admitted_at TIMESTAMPTZ,
    CONSTRAINT uq_queue_user UNIQUE (concert_id, user_id)   -- 중복 진입 차단
);
CREATE INDEX idx_queue_rebuild ON queue_entry(concert_id, status, seq, user_id);   -- ★ 커버링 — 복구 쿼리가 Index Only Scan

CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    event_id     UUID        NOT NULL UNIQUE,
    event_type   VARCHAR(64) NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ                             -- NULL = 미발행
);
CREATE INDEX idx_outbox_unpublished ON outbox(id) WHERE published_at IS NULL;  -- ★ 부분 인덱스
