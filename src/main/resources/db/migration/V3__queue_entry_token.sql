-- 입장 토큰 복구 (10/24) — 대기열 Redis 가 비워져도 이미 입장한 사람이 다시 줄 서지 않게, 입장 처리 때 토큰을 함께 남깁니다.
-- 10분짜리이고 본인(X-User-Id)과 짝일 때만 통하는 값이라 평문으로 둡니다. 이탈 · 재진입하면 비웁니다.
ALTER TABLE queue_entry ADD COLUMN entry_token VARCHAR(64);
