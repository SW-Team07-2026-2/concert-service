-- 입장 토큰 복구 (RedisQueueStore.restoreEntries) — 이미 있으면 두기 (NX)
-- ARGV[1] concertId, 이후 (uid, token, ttlMs) × N · 🔴 KEYS 밖의 키(entry)를 만집니다 — 단일 인스턴스 전제
for i = 2, #ARGV, 3 do
  redis.call('SET', 'entry:' .. ARGV[1] .. ':' .. ARGV[i], ARGV[i + 1], 'PX', ARGV[i + 2], 'NX')
end
return 1
