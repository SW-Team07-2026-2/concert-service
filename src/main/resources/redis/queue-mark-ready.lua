-- 재구성 완료 (RedisQueueStore.markReady) — seq 는 커질 때만 · 스냅샷 · 센티넬 기록 · 4개 키 만료
-- KEYS[1] queue:seq:{id}  KEYS[2] queue:snapshot:{id}  KEYS[3] queue:ready:{id}  KEYS[4] queue:concert:{id}
-- ARGV[1] maxSeq  ARGV[2] 만료 epoch 초
local cur = tonumber(redis.call('GET', KEYS[1]) or '0')
if tonumber(ARGV[1]) > cur then redis.call('SET', KEYS[1], ARGV[1]) end
redis.call('SET', KEYS[2], ARGV[1])
redis.call('SET', KEYS[3], '1')
for i = 1, 4 do redis.call('EXPIREAT', KEYS[i], ARGV[2]) end
return 1
