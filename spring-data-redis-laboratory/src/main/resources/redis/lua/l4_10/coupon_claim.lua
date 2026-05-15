-- ===========================================================================
-- 生产级·优惠券领取(一人一券 + 库存 + 核销码 + 有效期 + 领取榜)
-- ---------------------------------------------------------------------------
-- 业务意图:
--   ① 活动有效期校验(很多券活动没下线,薅羊毛入口)
--   ② 一人一券:Set 去重
--   ③ 总库存原子扣减
--   ④ 生成"核销码 claimId"(后续核销时用,不能让用户拿 userId 直接核销)
--   ⑤ 写一条领取记录(Hash:claimId → {userId,couponCode,expireAt,status})
--   ⑥ 维护"领取顺序榜"(ZSet,分数=领取时间戳),前 100 名打勋章
--   ⑦ 返回 cjson 字符串,字段够前端弹"恭喜你 / 库存剩余 / 核销码 / 有效期"
--
-- KEYS:
--   KEYS[1] = stock key       l4:10:coupon:{actId}:stock
--   KEYS[2] = user set        l4:10:coupon:{actId}:users        SADD userId
--   KEYS[3] = claim hash      l4:10:coupon:{actId}:claim:{userId}  field=claimId 等
--   KEYS[4] = claim seq       l4:10:coupon:{actId}:seq          INCR 生成 claimId
--   KEYS[5] = claim rank zset l4:10:coupon:{actId}:rank         前 N 名榜
-- ARGV:
--   ARGV[1] = userId
--   ARGV[2] = couponCodePrefix    券码前缀(如 "TEA20OFF")
--   ARGV[3] = nowMs
--   ARGV[4] = activityEndMs       活动结束时间(用于校验和券有效期)
--   ARGV[5] = couponValidMs       发放后券本身有效时长
--   ARGV[6] = topN                 前 N 名加勋章
--
-- 返回 JSON:
--   {
--     "code": 1,                  -- 1=成功 0=库存不足 -1=已领过 -2=活动结束 -9=参数非法
--     "msg": "ok",
--     "claimId": "CL-000123",     -- 核销码 ID
--     "couponCode": "TEA20OFF-9F2A1C",  -- 用户实际看到的券码
--     "remain": 4321,             -- 剩余库存
--     "claimedTotal": 5679,       -- 已领数
--     "rank": 5680,               -- 你是第几个领取者
--     "earnBadge": false,         -- 是否进入 topN 榜
--     "validUntilMs": 1717000000000, -- 券到期时间(毫秒)
--     "ts": 1715000000000
--   }
-- ===========================================================================

local stockKey  = KEYS[1]
local userSet   = KEYS[2]
local claimHash = KEYS[3]
local seqKey    = KEYS[4]
local rankKey   = KEYS[5]

local userId        = ARGV[1]
local prefix        = ARGV[2]
local nowMs         = tonumber(ARGV[3])
local activityEndMs = tonumber(ARGV[4])
local couponValidMs = tonumber(ARGV[5])
local topN          = tonumber(ARGV[6])

if not (userId and prefix and nowMs and activityEndMs and couponValidMs and topN) or userId == '' then
    return cjson.encode({code=-9, msg="invalid args", ts=nowMs or 0})
end

-- 活动结束
if nowMs >= activityEndMs then
    return cjson.encode({code=-2, msg="activity ended", ts=nowMs})
end

-- 重复领取
if redis.call('SISMEMBER', userSet, userId) == 1 then
    -- 把上次的核销码也带回去,提升体验(用户重新进入页面也能看到自己的券)
    local oldClaimId   = redis.call('HGET', claimHash, 'claimId')
    local oldCode      = redis.call('HGET', claimHash, 'couponCode')
    local oldValidMs   = redis.call('HGET', claimHash, 'validUntilMs')
    return cjson.encode({
        code=-1, msg="already claimed",
        claimId=oldClaimId or '', couponCode=oldCode or '',
        validUntilMs=tonumber(oldValidMs) or 0, ts=nowMs
    })
end

-- 库存判断
local stockRaw = redis.call('GET', stockKey)
if stockRaw == false then
    return cjson.encode({code=-9, msg="stock not initialized", ts=nowMs})
end
local stock = tonumber(stockRaw)
if not stock or stock <= 0 then
    return cjson.encode({code=0, msg="out of stock", remain=0, ts=nowMs})
end

-- 扣库存 + 入用户集合 + 顺序号
local remain = redis.call('DECR', stockKey)
redis.call('SADD', userSet, userId)
local seq = redis.call('INCR', seqKey)

-- 生成核销码(用 seq 取后 6 位 hex 防猜)
local claimId   = string.format("CL-%010d", seq)
local couponCode = string.format("%s-%06X", prefix, seq * 2654435761 % 16777216)
local validUntil = nowMs + couponValidMs

-- 写领取详情
redis.call('HSET', claimHash,
    'claimId', claimId,
    'couponCode', couponCode,
    'userId', userId,
    'validUntilMs', validUntil,
    'status', 'UNUSED',
    'claimedAt', nowMs)
redis.call('PEXPIREAT', claimHash, validUntil)

-- 排行榜
redis.call('ZADD', rankKey, nowMs, userId)
local rank = seq
local earnBadge = (rank <= topN)

return cjson.encode({
    code=1, msg="ok",
    claimId=claimId, couponCode=couponCode,
    remain=remain, claimedTotal=seq, rank=rank,
    earnBadge=earnBadge, validUntilMs=validUntil, ts=nowMs
})
