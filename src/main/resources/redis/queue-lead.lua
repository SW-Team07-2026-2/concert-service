-- 입장 처리기 리더 (RedisQueueStore.lead) — 내가 리더면 연장, 비어 있으면 차지
-- KEYS[1] queue:admitter:{id} · ARGV[1] owner  ARGV[2] TTL ms · 반환 1 리더 | 0
if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('PEXPIRE', KEYS[1], ARGV[2]) return 1 end
if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then return 1 end
return 0
