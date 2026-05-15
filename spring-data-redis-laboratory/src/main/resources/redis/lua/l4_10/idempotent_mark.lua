-- ===========================================================================
-- 生产级·业务级幂等门(请求去重 + 复用上次结果)
-- ---------------------------------------------------------------------------
-- 与"SET NX EX"的区别:
--   ① 幂等不仅是"挡住第二次",还要"把第一次的业务结果还回来",否则上游 retry
--      只能拿到 false → 误以为失败 → 又触发更多 retry。
--   ② 这里把"第一次的业务结果(JSON)"作为 value 存进去,第二次直接返回。
-- 适用:支付回调/MQ 重投/前端双击/HTTP 重试等所有"同一 requestId 第二次进来"场景
--
-- KEYS:
--   KEYS[1] = idempotent key   l4:10:idem:{biz}:{requestId}
-- ARGV:
--   ARGV[1] = requestId        审计/日志关联
--   ARGV[2] = ttl seconds      幂等保护时长(>= 业务最长重试窗口)
--   ARGV[3] = nowMs
--   ARGV[4] = initialResultJson 首次落地时要写入的"业务结果占位"(后续可被业务覆盖)
--                              如:{"status":"PROCESSING","createdAt":...}
--
-- 返回 JSON:
--   {
--     "firstTime": true|false,                  -- 是否首次
--     "code": 1,                                 -- 1=成功(首次或重放都给 1) -9=参数非法
--     "msg": "ok",
--     "previousResult": {...} | null,           -- 重放时:上次写入的业务结果(已 decode)
--     "ttlMsLeft": 86399000,                     -- 还能保护多久
--     "createdAt": 1715000000000,                -- 首次写入的 nowMs;重放时也是首次的
--     "ts": 1715000000123
--   }
-- ===========================================================================

local idemKey = KEYS[1]
local rid      = ARGV[1]
local ttl      = tonumber(ARGV[2])
local nowMs    = tonumber(ARGV[3])
local initial  = ARGV[4]

if not (idemKey and rid and ttl and nowMs and initial) or idemKey == '' or rid == '' then
    return cjson.encode({code=-9, msg="invalid args", ts=nowMs or 0})
end
if ttl <= 0 then
    return cjson.encode({code=-9, msg="non-positive ttl", ts=nowMs})
end

local prev = redis.call('GET', idemKey)
if prev then
    local pttl = redis.call('PTTL', idemKey)
    local ok, obj = pcall(cjson.decode, prev)
    return cjson.encode({
        firstTime=false, code=1, msg="duplicate",
        previousResult=(ok and obj or nil),
        ttlMsLeft=(pttl >= 0 and pttl or 0),
        ts=nowMs
    })
end

-- 首次写入
redis.call('SET', idemKey, initial, 'EX', ttl)
return cjson.encode({
    firstTime=true, code=1, msg="ok",
    previousResult=cjson.null,
    ttlMsLeft=ttl * 1000,
    createdAt=nowMs,
    ts=nowMs
})
