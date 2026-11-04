-- 락 해제 — 값이 내 토큰일 때만 DEL (TTL 이 지나 남이 잡은 락을 지우지 않게)
-- RedisQueueStore.unlockRebuild · RedisSeatLock 공용 · KEYS[1] 락 키 · ARGV[1] 토큰 · 반환 1 해제 | 0
if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
return 0
