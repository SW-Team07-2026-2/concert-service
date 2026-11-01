-- 입장 확인 (11/01 · 암표 방지) — 검표(TICKET_CHECKER)가 입장 QR 을 찍으면 한 번만 기록합니다 (조건부 UPDATE · 같은 QR 두 번 = 409)
ALTER TABLE booking ADD COLUMN checked_in_at TIMESTAMPTZ;   -- NULL = 아직 입장 전
ALTER TABLE booking ADD COLUMN checked_in_by BIGINT;        -- 검표한 관리자 계정 id (member.admin_account · FK 없음)
