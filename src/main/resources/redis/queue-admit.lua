-- 입장 처리 (RedisQueueStore.admit) — 앞에서부터 꺼내 생존 신호가 있으면 입장 토큰 발급, 없으면 이탈
-- KEYS[1] queue:concert:{id} · ARGV[1] concertId  ARGV[2] 최대 꺼낼 수  ARGV[3] 입장 토큰 TTL 초  ARGV[4..] 토큰
-- 반환 [uid, seq, 1(입장)|0(이탈), …] · 🔴 KEYS 밖의 키(alive · entry)를 만집니다 — 단일 인스턴스 전제
local out = {}
local want = #ARGV - 3
local given, pops = 0, 0
while given < want and pops < tonumber(ARGV[2]) do
  local p = redis.call('ZPOPMIN', KEYS[1])
  if #p == 0 then break end
  pops = pops + 1
  if redis.call('EXISTS', 'queue:alive:' .. ARGV[1] .. ':' .. p[1]) == 1 then
    given = given + 1
    redis.call('SET', 'entry:' .. ARGV[1] .. ':' .. p[1], ARGV[3 + given], 'EX', ARGV[3])
    table.insert(out, p[1]); table.insert(out, p[2]); table.insert(out, '1')
  else
    table.insert(out, p[1]); table.insert(out, p[2]); table.insert(out, '0')
  end
end
return out
