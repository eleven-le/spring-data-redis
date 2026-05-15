-- ===========================================================================
-- 生产级·延迟队列原子抢占(含任务详情拉取 + 抢占次数计数 + 异常任务拣回)
-- ---------------------------------------------------------------------------
-- 茶饮订单"15 分钟未支付自动取消"的真实流水:
--   ① 多消费者 ZRANGEBYSCORE 拿候选 → ZREM 抢占 → 抢到才算
--   ② 抢到后立刻把任务 payload 从 detailHash 拉出来一起返回(下游不用再回扣 redis)
--   ③ 同时把 attempts 自增 1(超过 maxAttempts 的进 dead letter zset)
--   ④ 抢到的任务还要写入"in-flight set"(挂着 visibilityTimeout),消费失败可被回滚
--
-- KEYS:
--   KEYS[1] = ready zset      l4:10:delay:{biz}:ready       score=触发时间
--   KEYS[2] = detail hash     l4:10:delay:{biz}:detail      field=taskId 值=payloadJson
--   KEYS[3] = attempt hash    l4:10:delay:{biz}:attempts    field=taskId 值=次数
--   KEYS[4] = inflight zset   l4:10:delay:{biz}:inflight    score=visibilityDeadlineMs
--   KEYS[5] = dead zset       l4:10:delay:{biz}:dead        进 DLQ 的任务
-- ARGV:
--   ARGV[1] = nowMs
--   ARGV[2] = limit               本次最多抢几个
--   ARGV[3] = visibilityMs        抢到后多久没 ack 视为消费失败
--   ARGV[4] = maxAttempts         超过则进 dead letter
--
-- 返回 JSON:
--   {
--     "code": 1, "msg": "ok",
--     "claimedCount": 3,
--     "claimed": [
--       {"taskId":"order-1001","attempts":1,"payload":"{...}","scheduledAtMs":...,"visibilityDeadlineMs":...},
--       ...
--     ],
--     "deadLetter": ["order-9999"],   -- 这次直接被丢进 DLQ 的任务(尝试次数已超限)
--     "ts": 1715000000000
--   }
-- ===========================================================================

local readyZ    = KEYS[1]
local detailH   = KEYS[2]
local attemptH  = KEYS[3]
local inflightZ = KEYS[4]
local deadZ     = KEYS[5]

local now      = tonumber(ARGV[1])
local lim      = tonumber(ARGV[2])
local visMs    = tonumber(ARGV[3])
local maxAtt   = tonumber(ARGV[4])

if not (now and lim and visMs and maxAtt) or lim <= 0 or visMs <= 0 or maxAtt <= 0 then
    return cjson.encode({code=-9, msg="invalid args", claimedCount=0, claimed={}, deadLetter={}, ts=now or 0})
end

local cands = redis.call('ZRANGEBYSCORE', readyZ, '-inf', now, 'LIMIT', 0, lim)
local claimed = {}
local dead = {}
local visDeadline = now + visMs

for i = 1, #cands do
    local taskId = cands[i]
    -- 抢占:只有 ZREM 真的删除了那条,才算我抢到
    if redis.call('ZREM', readyZ, taskId) == 1 then
        local attempts = redis.call('HINCRBY', attemptH, taskId, 1)
        if attempts > maxAtt then
            -- 进死信
            redis.call('ZADD', deadZ, now, taskId)
            redis.call('HDEL', attemptH, taskId)
            -- 详情保留以便人工诊断,这里不删 detail
            dead[#dead + 1] = taskId
        else
            local payload = redis.call('HGET', detailH, taskId)
            redis.call('ZADD', inflightZ, visDeadline, taskId)
            claimed[#claimed + 1] = {
                taskId = taskId,
                attempts = attempts,
                payload = payload or '',
                scheduledAtMs = now,           -- 真实写法应在 detail 里存原 trigger time;此处简化
                visibilityDeadlineMs = visDeadline
            }
        end
    end
end

return cjson.encode({
    code=1, msg="ok",
    claimedCount=#claimed, claimed=claimed,
    deadLetter=dead, ts=now
})
