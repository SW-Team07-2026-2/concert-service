-- 무대 그리기 원본 (가안 · 10/24) — GPU 서버가 보내는 JSON 을 형식이 정해지기 전까지 통째로 보관합니다.
-- 형식이 정해지면 필요한 값을 정식 컬럼(15 I18 의 venue.stage_poly 등)으로 매핑하는 마이그레이션을 따로 추가합니다.

CREATE TABLE stage_drawing (
    venue_id    BIGINT      PRIMARY KEY REFERENCES venue(id),   -- 공연장당 최신 1건 — 다시 보내면 덮어씀
    payload     JSONB       NOT NULL,
    received_at TIMESTAMPTZ NOT NULL
);
