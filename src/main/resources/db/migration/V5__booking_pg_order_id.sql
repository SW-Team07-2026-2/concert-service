-- 토스페이먼츠 전환 (10/28) — Payment prepare 가 paymentKey 대신 토스 orderId(pg_order_id)를 줍니다.
-- payment_key 는 더 이상 쓰지 않습니다 (구버전과 공존하는 롤링 배포를 위해 남겨둠 — 다음 정리 때 DROP)
ALTER TABLE booking ADD COLUMN pg_order_id VARCHAR(64);   -- NULL = 준비 전
