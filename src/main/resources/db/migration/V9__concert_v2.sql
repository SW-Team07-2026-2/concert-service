-- 11/13 concert v2 — 공연 · 공연장 배치는 관리자(Studio)를 거쳐서만 들어옵니다 (수집기 직접 적재 경로 삭제).
--   공연장 배치  venue ─< floor ─< section ─< seat            (무대 그리기 결과 · 공연장에 한 번 등록 · 위치만)
--   판매        concert ─< concert_seat >─ seat              (공연마다 좌석별 가격 · 상태)
--   예매        booking ─< booking_seat >─ concert_seat      (1인 N매 · concert.max_seats_per_user)
--   라인업      concert ─< concert_artist >─ artist          (artist 는 Catalog artist.updated 복제)
-- 올라간 데이터가 없어 V1 ~ V8 테이블을 지우고 새로 만듭니다 (catalog V5 와 같은 방식 — Catalog 아티스트 id 도 이때 새로 매겨집니다).
-- outbox 는 구조가 같아 그대로 둡니다.

DROP TABLE IF EXISTS booking_seat, seat, booking, queue_entry, stage_drawing, concert, venue CASCADE;

-- ── 공연장 배치 (위치) ─────────────────────────────────────────
CREATE TABLE venue (
    id       BIGSERIAL PRIMARY KEY,
    name     VARCHAR(100) NOT NULL,
    region   VARCHAR(16)  NOT NULL,                      -- SEOUL · GYEONGGI … (Region 코드표)
    address  VARCHAR(255),
    capacity INTEGER CHECK (capacity > 0),
    CONSTRAINT uq_venue_name UNIQUE (name)
);
CREATE INDEX idx_venue_region ON venue(region);

CREATE TABLE floor (
    id       BIGSERIAL PRIMARY KEY,
    venue_id BIGINT      NOT NULL REFERENCES venue(id),
    name     VARCHAR(16) NOT NULL,                       -- '1F' · '2F' (무대 그리기 값 그대로)
    CONSTRAINT uq_floor UNIQUE (venue_id, name)          -- venue_id 로 시작 — FK 조회도 이 인덱스로
);

-- shape · x · y 는 무대 그리기 캔버스 기준 0~1 비율, angle 은 무대 기준 기울기(도) — 프론트가 실제 배치 모양 그대로 그립니다
CREATE TABLE section (
    id       BIGSERIAL PRIMARY KEY,
    floor_id BIGINT       NOT NULL REFERENCES floor(id),
    name     VARCHAR(16)  NOT NULL,
    shape    JSONB        NOT NULL,                      -- [[x, y], ...] 꼭짓점 3개 이상
    angle    NUMERIC(5,1) NOT NULL,
    CONSTRAINT uq_section UNIQUE (floor_id, name),
    CONSTRAINT ck_section_shape CHECK (jsonb_typeof(shape) = 'array' AND jsonb_array_length(shape) >= 3)
);

CREATE TABLE seat (
    id         BIGSERIAL PRIMARY KEY,
    section_id BIGINT       NOT NULL REFERENCES section(id),
    row_no     INTEGER      NOT NULL CHECK (row_no > 0),
    seat_no    INTEGER      NOT NULL CHECK (seat_no > 0),
    x          NUMERIC(5,4) NOT NULL CHECK (x BETWEEN 0 AND 1),
    y          NUMERIC(5,4) NOT NULL CHECK (y BETWEEN 0 AND 1),
    angle      NUMERIC(5,1) NOT NULL,
    CONSTRAINT uq_seat_position UNIQUE (section_id, row_no, seat_no)
);

-- ── 공연 · 라인업 ──────────────────────────────────────────────
-- Catalog artist.updated 복제 — 라인업 API 는 id 만 받고 이름 · 장르는 여기서 (장르는 Catalog Genre 코드 하나로 통일)
CREATE TABLE artist (
    id         BIGINT       PRIMARY KEY,                 -- catalog.artist.id (스키마 간 FK 없음 · 값만 복제)
    name       VARCHAR(100) NOT NULL,
    genre      VARCHAR(32),                              -- 대표 장르 (INDIE_ROCK …) · 없으면 NULL
    updated_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE concert (
    id                 BIGSERIAL PRIMARY KEY,
    venue_id           BIGINT       NOT NULL REFERENCES venue(id),
    title              VARCHAR(200) NOT NULL,
    type               VARCHAR(16)  NOT NULL,            -- SOLO | FESTIVAL
    genre              VARCHAR(32),                      -- SOLO = 그 아티스트 장르 (artist.updated 로 함께 갱신) · FESTIVAL = 관리자가 지정
    starts_at          TIMESTAMPTZ  NOT NULL,
    ends_at            TIMESTAMPTZ,
    price              INTEGER CHECK (price >= 0),       -- 목록에 보여 줄 대표 가격 · 결제 금액은 concert_seat.price
    booking_open_at    TIMESTAMPTZ,                      -- NULL = 미정
    queue_enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    max_seats_per_user INTEGER      NOT NULL CHECK (max_seats_per_user BETWEEN 1 AND 10),   -- 1인 N매
    source             VARCHAR(32),                      -- 수집 원본 (관리자가 수집 데이터로 등록할 때만) — 같은 공연 두 번 등록 방지
    external_id        VARCHAR(128),
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_concert_ends_after_start CHECK (ends_at > starts_at),
    CONSTRAINT ck_concert_open_before_start CHECK (booking_open_at < starts_at),
    CONSTRAINT ck_concert_source CHECK ((source IS NULL) = (external_id IS NULL))
);
CREATE UNIQUE INDEX uq_concert_source ON concert(source, external_id) WHERE source IS NOT NULL;
CREATE INDEX idx_concert_starts ON concert(starts_at);
CREATE INDEX idx_concert_venue ON concert(venue_id, starts_at);
CREATE INDEX idx_concert_genre ON concert(genre, starts_at);

CREATE TABLE concert_artist (
    concert_id    BIGINT  NOT NULL REFERENCES concert(id),
    artist_id     BIGINT  NOT NULL REFERENCES artist(id),
    billing_order INTEGER NOT NULL CHECK (billing_order > 0),   -- 라인업 순서 (1 = 헤드라이너)
    PRIMARY KEY (concert_id, artist_id),                        -- 같은 아티스트가 두 번 들어가지 않게
    CONSTRAINT uq_concert_artist_order UNIQUE (concert_id, billing_order)
);
CREATE INDEX idx_concert_artist_artist ON concert_artist(artist_id, concert_id);   -- 아티스트별 공연 · 캘린더 · 장르 갱신

-- ── 예매 ───────────────────────────────────────────────────────
CREATE TABLE booking (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT      NOT NULL,                -- member.users (FK 없음)
    concert_id      BIGINT      NOT NULL REFERENCES concert(id),
    status          VARCHAR(16) NOT NULL,                -- PENDING | CONFIRMED | CANCELLED | EXPIRED
    total_price     INTEGER     NOT NULL CHECK (total_price > 0),   -- 담은 좌석 가격의 합
    pg_order_id     VARCHAR(64),                         -- NULL = 결제 준비 전
    hold_expires_at TIMESTAMPTZ,                         -- 선점 서버 마감 (화면 7분 + 유예 179초 · DB now() 기준)
    paying_at       TIMESTAMPTZ,                         -- 결제 승인 시작
    checked_in_at   TIMESTAMPTZ,                         -- NULL = 아직 입장 전
    checked_in_by   BIGINT,                              -- member.admin_account (FK 없음)
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_booking_user ON booking(user_id, id DESC);
CREATE INDEX idx_booking_hold ON booking(hold_expires_at) WHERE status = 'PENDING';
-- ★ 1인 1예매 — 진행 중 · 확정 예매는 공연당 1건 (그 안에 좌석 최대 max_seats_per_user 개)
CREATE UNIQUE INDEX uq_booking_active ON booking(concert_id, user_id) WHERE status IN ('PENDING', 'CONFIRMED');

-- ── 공연 좌석 (판매 상태) ───────────────────────────────────────
CREATE TABLE concert_seat (
    id              BIGSERIAL PRIMARY KEY,
    concert_id      BIGINT      NOT NULL REFERENCES concert(id),
    seat_id         BIGINT      NOT NULL REFERENCES seat(id),
    price           INTEGER     NOT NULL CHECK (price > 0),
    status          VARCHAR(16) NOT NULL,                -- AVAILABLE | HELD | PAYING | SOLD
    held_by         BIGINT,                              -- member.users (FK 없음)
    hold_expires_at TIMESTAMPTZ,
    booking_id      BIGINT REFERENCES booking(id),       -- 지금 이 좌석을 쥔 예매 (취소 · 다음 사람 선점 때 바뀜)
    CONSTRAINT uq_concert_seat UNIQUE (concert_id, seat_id)   -- concert_id 로 시작 — 좌석 화면 조회도 이 인덱스로
);
CREATE INDEX idx_concert_seat_seat ON concert_seat(seat_id);   -- 배치를 쓰는 공연 좌석 확인(409) · seat FK 검사
CREATE INDEX idx_concert_seat_holder ON concert_seat(concert_id, held_by) WHERE status = 'HELD';
CREATE INDEX idx_concert_seat_expiry ON concert_seat(hold_expires_at) WHERE status = 'HELD';
CREATE INDEX idx_concert_seat_booking ON concert_seat(booking_id);

CREATE TABLE booking_seat (                              -- 예매가 잡았던 좌석 이력 (concert_seat.booking_id 는 덮어써지므로 여기 남김)
    booking_id      BIGINT NOT NULL REFERENCES booking(id),
    concert_seat_id BIGINT NOT NULL REFERENCES concert_seat(id),
    PRIMARY KEY (booking_id, concert_seat_id)
);
CREATE INDEX idx_booking_seat_seat ON booking_seat(concert_seat_id);   -- 좌석 → 예매 이력 · FK 검사

-- ── 대기열 스냅샷 (구조 그대로) ─────────────────────────────────
CREATE TABLE queue_entry (
    id          BIGSERIAL   PRIMARY KEY,
    concert_id  BIGINT      NOT NULL REFERENCES concert(id),
    user_id     BIGINT      NOT NULL,                    -- member.users (FK 없음)
    seq         BIGINT      NOT NULL,                    -- ★ Redis score 와 동일 (순서의 진실)
    status      VARCHAR(12) NOT NULL,                    -- WAITING | ADMITTED | LEFT
    entered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    admitted_at TIMESTAMPTZ,
    entry_token VARCHAR(64),                             -- ADMITTED 일 때만 — Redis 복구 원천
    CONSTRAINT uq_queue_user UNIQUE (concert_id, user_id)
);
CREATE INDEX idx_queue_rebuild ON queue_entry(concert_id, status, seq, user_id);   -- ★ 커버링 — 복구 쿼리가 Index Only Scan
