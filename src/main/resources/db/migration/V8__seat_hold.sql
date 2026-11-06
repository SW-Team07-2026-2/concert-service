-- 11/04 77A 좌석 선점 (티켓링크 방식) — 좌석을 고르는 순간(N10) 한 명만 잡고, 화면 8분 · 서버 9분이 지나면 풀립니다.
-- 10/27 좌석 경합(고를 때 안 잡고 결제 승인 때 확보)을 대체합니다. seat 의 held_by · booking_id · hold_expires_at 은 V1 의 점유 컬럼을 다시 씁니다.
--   seat.status   AVAILABLE | HELD(선점) | PAYING(결제 승인 중 — 마감이 지나도 남이 못 가져감) | SOLD
--   마감이 지난 HELD 는 정리를 기다리지 않고 다음 사람이 바로 선점합니다 (N10 조건부 UPDATE).
-- 🔴 선점 시간 비교는 전부 DB now() — 앱 서버 시계는 쓰지 않습니다.

ALTER TABLE booking ADD COLUMN hold_expires_at TIMESTAMPTZ;   -- 선점 서버 마감 (N10 때 now() + 9분 · 바뀌지 않음) — 좌석이 다음 사람에게 넘어가도 이 예매의 정리 기준
ALTER TABLE booking ADD COLUMN paying_at TIMESTAMPTZ;         -- 결제 승인 시작 (좌석 PAYING) — 응답을 못 받으면 1분 뒤 정리가 Payment 에 결과를 묻습니다

-- 배포 전 경합 시절 데이터
UPDATE booking SET paying_at = now()
WHERE status = 'PENDING' AND id IN (SELECT booking_id FROM seat WHERE status = 'HELD');   -- 경합 시절 HELD = 승인 중 → PAYING 으로 (정리가 결제 상태로 확정 · 반환)
UPDATE seat SET status = 'PAYING' WHERE status = 'HELD';
UPDATE booking SET hold_expires_at = now() WHERE status = 'PENDING';                       -- 좌석을 잡지 않았던 선택은 바로 정리 → 다시 고르기

DROP INDEX idx_booking_pending;                                                            -- 고른 시각(created_at) 기준 → 선점 마감 기준
CREATE INDEX idx_booking_hold ON booking(hold_expires_at) WHERE status = 'PENDING';
