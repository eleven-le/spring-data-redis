-- ===========================================================================
-- 生产级·分布式锁安全释放(compare-and-delete + 持有诊断)
-- ---------------------------------------------------------------------------
-- 真实生产事故 9 成出在锁释放:
--   ① 直接 DEL 误删别人锁 → 必须 GET == myToken 才 DEL
--   ② "我以为我还持有,其实锁已被自动过期且被别人抢走了" → 这种情况要可观测
--   ③ 持有时长统计是定位"业务跑太久导致锁被自动释放"的关键证据
--
-- KEYS:
--   KEYS[1] = lock key       l4:10:lock:{biz}:{resourceId}
--   KEYS[2] = lock meta hash l4:10:lock:{biz}:{resourceId}:meta  存 acquiredAt 等
-- ARGV:
--   ARGV[1] = myToken        本持有者唯一 token
--   ARGV[2] = nowMs
--
-- 返回 JSON:
--   {
--     "code": 1,                  -- 1=正常释放 0=token 不匹配 -1=锁已过期/不存在 -9=参数非法
--     "msg": "...",
--     "released": true|false,
--     "heldMs": 1234,             -- 持有时长(过期/不匹配时为 0)
--     "currentHolder": "tok-xxx" | null,  -- 锁还在,且不是你的:当前持有者(诊断用)
--     "ts": 1715000000000
--   }
-- ===========================================================================

local lockKey = KEYS[1]
local metaKey = KEYS[2]
local myToken = ARGV[1]
local nowMs   = tonumber(ARGV[2])

if not (lockKey and metaKey and myToken and nowMs) or myToken == '' then
    return cjson.encode({code=-9, msg="invalid args", released=false, heldMs=0, ts=nowMs or 0})
end

local cur = redis.call('GET', lockKey)
if cur == false then
    -- 锁已不存在(自动过期 / 被人抢释放)
    return cjson.encode({
        code=-1, msg="lock expired or released",
        released=false, heldMs=0, currentHolder=cjson.null, ts=nowMs
    })
end

if cur ~= myToken then
    -- 关键事件:你以为你持有,实际不是。生产里这一行直接接告警
    return cjson.encode({
        code=0, msg="token mismatch (held by someone else)",
        released=false, heldMs=0, currentHolder=cur, ts=nowMs
    })
end

-- 计算持有时长
local acquiredAtRaw = redis.call('HGET', metaKey, 'acquiredAt')
local acquiredAt = tonumber(acquiredAtRaw) or nowMs
local heldMs = nowMs - acquiredAt

redis.call('DEL', lockKey)
redis.call('DEL', metaKey)

return cjson.encode({
    code=1, msg="released",
    released=true, heldMs=heldMs,
    currentHolder=cjson.null, ts=nowMs
})
