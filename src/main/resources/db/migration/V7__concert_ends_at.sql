-- 공연 끝 시각 (11/04 T3B) — 입장 QR 마감 · 검표 화면 공연 목록에 씁니다. NULL = 모름 → 시작 + entry.close-after-hours
ALTER TABLE concert ADD COLUMN ends_at TIMESTAMPTZ;
ALTER TABLE concert ADD CONSTRAINT ck_concert_ends_after_start CHECK (ends_at > starts_at);   -- NULL 은 통과
