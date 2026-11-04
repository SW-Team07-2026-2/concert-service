-- 대기열 진입 (RedisQueueStore.enter) — 반환 {0 새로 진입 | 1 이미 대기 중 | 2 이미 입장, rank, size}
-- KEYS[1] queue:concert:{id}  KEYS[2] queue:seq:{id}  KEYS[3] queue:alive:{id}:{uid}  KEYS[4] entry:{id}:{uid}
-- ARGV[1] userId  ARGV[2] 만료 epoch 초  ARGV[3] 생존 신호 TTL 초
if redis.call('EXISTS', KEYS[4]) == 1 then return {2, 0, redis.call('ZCARD', KEYS[1])} end
local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
if rank then
  redis.call('SET', KEYS[3], '1', 'EX', ARGV[3])
  return {1, rank, redis.call('ZCARD', KEYS[1])}
end
local seq = redis.call('INCR', KEYS[2])
redis.call('ZADD', KEYS[1], seq, ARGV[1])
redis.call('EXPIREAT', KEYS[1], ARGV[2])
redis.call('EXPIREAT', KEYS[2], ARGV[2])
redis.call('SET', KEYS[3], '1', 'EX', ARGV[3])
return {0, redis.call('ZRANK', KEYS[1], ARGV[1]), redis.call('ZCARD', KEYS[1])}
