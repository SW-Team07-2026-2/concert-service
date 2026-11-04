-- 10/27 좌석 경합 (FR-CON-23) — 좌석을 고를 때 잡지 않습니다(점유 N8 폐지).
-- 같은 좌석을 여러 명이 고를 수 있고(booking PENDING = 경합), 먼저 결제 승인(N16)한 한 명만 SOLD.
-- seat.status HELD 는 이제 "결제 승인 중"(수 초) — held_by · hold_expires_at · booking_id 는 승인 중인 예매입니다.
-- V1 의 컬럼 주석(점유)은 Flyway 체크섬 때문에 고치지 않습니다.

-- 선택 만료 스캔 (BookingExpiryJob · 30초) — 결제하지 않고 3분이 지난 PENDING
CREATE INDEX idx_booking_pending ON booking(created_at) WHERE status = 'PENDING';
