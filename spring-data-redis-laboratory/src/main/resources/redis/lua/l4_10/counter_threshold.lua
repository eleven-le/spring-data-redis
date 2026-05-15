-- ===========================================================================
-- 生产级·计数器阈值限制(返回当前值/阈值距离/告警水位/TTL)
-- ---------------------------------------------------------------------------
-- 用于"每日签到/领券/转赠/充值"等所有"在窗口内有上限"的场景。
-- 比"INCR 后再判"安全:不会出现"INCR 完发现超了又 DECR"的中间错误态。
--
-- 生产级返回字段考量:
--   ① 客户端要知道"我还能做几次" → distance
--   ② 监控要知道"是否进入告警水位 80%" → alarm
--   ③ 排查要知道"这个计数离过期还有多久" → ttlMs
--
-- KEYS:
--   KEYS[1] = counter key     l4:10:counter:{userId}:{action}
-- ARGV:
--   ARGV[1] = delta           本次 +n
--   ARGV[2] = threshold       上限
--   ARGV[3] = ttlSec          首次写入设置的 TTL
--   ARGV[4] = alarmPercent    告警水位(0-100,如 80 表示到达 80% 设 alarm=true)
--   ARGV[5] = nowMs
--
-- 返回 JSON:
--   {
--     "code": 1,                  -- 1=放行 0=超阈值未写入 -9=参数非法
--     "msg": "...",
--     "current": 7,
--     "threshold": 10,
--     "delta": 1,
--     "distance": 3,              -- 距离阈值还能做几次
--     "ttlMs": 86399000,
--     "alarm": false,             -- 是否进入告警水位
--     "ts": 1715000000000
--   }
-- ===========================================================================

local key = KEYS[1]
local delta = tonumber(ARGV[1])
local threshold = tonumber(ARGV[2])
local ttl = tonumber(ARGV[3])
local alarmPercent = tonumber(ARGV[4])
local nowMs = tonumber(ARGV[5])

if not (key and delta and threshold and ttl and alarmPercent and nowMs)
   or delta <= 0 or threshold <= 0 or ttl <= 0 or alarmPercent < 0 or alarmPercent > 100 then
    return cjson.encode({code=-9, msg="invalid args", ts=nowMs or 0})
end

local raw = redis.call('GET', key)
local cur = 0
if raw then
    cur = tonumber(raw)
    if not cur then
        return cjson.encode({code=-9, msg="counter corrupted", ts=nowMs})
    end
end

if cur + delta > threshold then
    local pttl = redis.call('PTTL', key)
    return cjson.encode({
        code=0, msg="threshold exceeded",
        current=cur, threshold=threshold, delta=delta,
        distance=threshold-cur, ttlMs=(pttl >= 0 and pttl or 0),
        alarm=true, ts=nowMs
    })
end

local newVal = redis.call('INCRBY', key, delta)
if cur == 0 then
    redis.call('EXPIRE', key, ttl)
end
local pttl = redis.call('PTTL', key)
local alarm = (newVal * 100 >= threshold * alarmPercent)

return cjson.encode({
    code=1, msg="ok",
    current=newVal, threshold=threshold, delta=delta,
    distance=threshold-newVal, ttlMs=(pttl >= 0 and pttl or 0),
    alarm=alarm, ts=nowMs
})
