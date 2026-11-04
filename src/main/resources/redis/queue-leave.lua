-- 대기 취소 (RedisQueueStore.leave) — 반환 빠지기 전 seq (없었으면 nil)
-- KEYS[1] queue:concert:{id}  KEYS[2] queue:alive:{id}:{uid}  KEYS[3] entry:{id}:{uid} · ARGV[1] userId
local s = redis.call('ZSCORE', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('DEL', KEYS[2], KEYS[3])
return s
