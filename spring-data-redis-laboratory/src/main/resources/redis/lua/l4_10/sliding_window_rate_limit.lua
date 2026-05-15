---@diagnostic disable: undefined-global
-- ===========================================================================
-- 生产级·滑动窗口限流(返回剩余配额 + 重置时间 + 当前窗内最早请求)
-- ---------------------------------------------------------------------------
-- 与"只返回 1/0"的玩具版区别:
--   生产级限流必须告诉客户端:
--     ① 你还能调几次(remaining)
--     ② 多久后窗口滑出有空位(retryAfterMs)
--     ③ 当前窗内最早一次请求时间(用于客户端做指数退避)
--   这些字段对应 HTTP 标准头:X-RateLimit-Remaining / X-RateLimit-Reset。
--
-- KEYS:
--   KEYS[1] = zset key   l4:10:rate:{userId}:{api}
-- ARGV:
--   ARGV[1] = nowMs
--   ARGV[2] = windowMs
--   ARGV[3] = capacity        窗内最大请求数
--   ARGV[4] = requestId       member 必须唯一
--   ARGV[5] = ttlSec
--
-- 返回 JSON:
--   {
--     "allowed": true|false,
--     "code": 1|0|-9,             -- 1=放行 0=限流 -9=参数非法
--     "used": 7,                   -- 当前窗内已使用次数(放行后含本次)
--     "capacity": 10,
--     "remaining": 3,
--     "oldestTs": 1715...,         -- 当前窗内最早请求时间(空窗口=nowMs)
--     "retryAfterMs": 234,         -- 限流时:下一次重试建议等多久
--     "ts": 1715000000000
--   }
-- ===========================================================================

local key = KEYS[1]
local now = tonumber(ARGV[1])
local win = tonumber(ARGV[2])
local cap = tonumber(ARGV[3])
local rid = ARGV[4]
local ttl = tonumber(ARGV[5])

if not (key and now and win and cap and rid and ttl) or rid == '' then
    return cjson.encode({allowed=false, code=-9, msg="invalid args", ts=now or 0})
end
if win <= 0 or cap <= 0 or ttl <= 0 then
    return cjson.encode({allowed=false, code=-9, msg="non-positive args", ts=now})
end

-- 1) 清理窗口外
redis.call('ZREMRANGEBYSCORE', key, '-inf', now - win)
-- 2) 当前窗内
local used = redis.call('ZCARD', key)
local oldest = now
local oldestArr = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
if oldestArr[2] then oldest = tonumber(oldestArr[2]) end

if used >= cap then
    -- 限流:下次最早可用时间 = 最早一条滑出窗口的时刻
    local retryAfter = (oldest + win) - now
    if retryAfter < 0 then retryAfter = 0 end
    return cjson.encode({
        allowed=false, code=0, msg="rate limited",
        used=used, capacity=cap, remaining=0,
        oldestTs=oldest, retryAfterMs=retryAfter, ts=now
    })
end

-- 3) 放行
redis.call('ZADD', key, now, rid)
redis.call('EXPIRE', key, ttl)
local newUsed = used + 1

return cjson.encode({
    allowed=true, code=1, msg="ok",
    used=newUsed, capacity=cap, remaining=cap-newUsed,
    oldestTs=oldest, retryAfterMs=0, ts=now
})
