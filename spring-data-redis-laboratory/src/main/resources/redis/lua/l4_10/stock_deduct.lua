---@diagnostic disable: undefined-global
-- ===========================================================================
-- 生产级·秒杀/抢购库存原子扣减(含 用户限购 + 幂等 + 扣减流水)
-- ---------------------------------------------------------------------------
-- 业务意图(C 端茶饮限量抢购为例):
--   ① 活动是否还在售:用 activityEndMs 兜底,避免运营忘了关
--   ② 同一 requestId 重复请求只生效一次(幂等):MQ 重投/前端双击场景
--   ③ 用户级限购:整个活动每人最多买 N 杯,跨设备
--   ④ 总库存原子扣减
--   ⑤ 写一条扣减流水(taskId/qty/userId/ts) → 之后异步落 DB / MQ
--   ⑥ 返回 cjson 字符串,字段够 Java 侧画面 / 风控 / 异常诊断
--
-- KEYS:
--   KEYS[1] = sku stock key            l4:10:stock:sku:{tag}
--   KEYS[2] = user purchased hash      l4:10:user_purchased:{tag}    field=userId 值=已购数
--   KEYS[3] = sku tx stream (LIST)     l4:10:stock:tx:{tag}          扣减流水(LPUSH)
--   KEYS[4] = idempotent key           l4:10:idem:{tag}:requestId    防重复扣减
--   KEYS[5] = global tx counter        l4:10:txid:{tag}              全局自增,生成 txId
-- ARGV:
--   ARGV[1] = qty            本次扣减杯数
--   ARGV[2] = userId
--   ARGV[3] = userMaxLimit   用户在本活动总购买上限
--   ARGV[4] = requestId      幂等 token,客户端生成的 uuid
--   ARGV[5] = nowMs          客户端时钟(用于流水时间戳;不要用 redis time 给 Cluster 加复杂度)
--   ARGV[6] = activityEndMs  活动截止毫秒
--   ARGV[7] = idemTtlSec     幂等记录 TTL(一般 24h)
--   ARGV[8] = streamMaxLen   流水最大保留条数(LTRIM 上限)
--
-- 返回:cjson 字符串,字段固定如下(Java 侧用 Jackson readValue 解析为 StockDeductResult):
--   {
--     "code": 1,                         -- 1=成功 0=库存不足 -1=SKU不存在 -2=用户超限
--     "msg": "ok",                       --                  -3=活动结束 -4=幂等命中(原值) -9=参数非法
--     "txId": "tx-{tag}-000001234",      -- 扣减流水号(幂等命中也会回传上次的)
--     "qty": 2,                          -- 本次实际扣减
--     "remain": 998,                     -- 扣减后剩余库存
--     "userPurchased": 3,                -- 此用户在本活动累计购买
--     "userLimitLeft": 7,                -- 此用户还能买几杯
--     "ts": 1715000000000,               -- 扣减时间戳
--     "idempotent": false                -- 是否是幂等命中(true=请求重放)
--   }
-- ===========================================================================






-- ========================================================================================================================================================================
-- 1. 接收参数 (类似 Spring 接口的入参绑定)
-- ========================================================================================================================================================================
-- ⚠️ 避坑提醒：在 Redis Cluster 下，这 5 个 key 的 {hash_tag} 必须完全一致！
local stockKey   = KEYS[1] -- [String] SKU总库存键
local userPurKey = KEYS[2] -- [Hash] 记录每个 userId 已购买的数量 (类似 Map<UserId, Count>)
local txStream   = KEYS[3] -- [List] 扣减流水，用于 Java 端异步消费落库
local idemKey    = KEYS[4] -- [String] 防重放的幂等 Key (通常包含 requestId)
local txCounter  = KEYS[5] -- [String] 全局自增发号器，用于生成唯一流水号 txId


-- 注意：ARGV 传进来的默认全是 String，必须用 tonumber 转成数字才能比大小
local qty           = tonumber(ARGV[1]) -- 本次请求购买的数量
local userId        = ARGV[2]              -- 用户 ID (字符串，不用转)
local userMaxLimit  = tonumber(ARGV[3]) -- 该用户在此活动中的最大购买上限
local requestId     = ARGV[4]              -- 客户端生成的唯一请求ID (防重放凭证)
local nowMs         = tonumber(ARGV[5]) -- Java 端传入的当前系统时间戳
local activityEndMs = tonumber(ARGV[6]) -- 活动结束时间戳
local idemTtl       = tonumber(ARGV[7]) -- 幂等记录的过期时间(秒)，一般设置为 24 小时
local streamMaxLen  = tonumber(ARGV[8]) -- 流水 List 的最大保留长度 (防止 OOM 撑爆内存)

-- ========================================================================================================================================================================
-- Step 0. 参数校验任何一个不合法都不能改任何状态 (类似 Java 里的 Assert.notNull)
-- ========================================================================================================================================================================
-- Lua 里除了 nil 和 false，其他全被视为 true。这里确保所有参数都不为空，且转数字没报错
if not (qty and userId and userMaxLimit and requestId and nowMs and activityEndMs and idemTtl and streamMaxLen) then
    return cjson.encode({code=-9, msg="invalid args", ts=nowMs or 0})
end
-- 严谨起见，防止传入负数库存或负数限制
if qty <= 0 or userMaxLimit <= 0 or idemTtl <= 0 then
    return cjson.encode({code=-9, msg="non-positive args", ts=nowMs})
end
if userId == '' or requestId == '' then
    return cjson.encode({code=-9, msg="empty userId/requestId", ts=nowMs})
end

-- ========================================================================================================================================================================
-- Step 1. 幂等命中:返回上次写入的结果(SET 时存的就是 JSON 字符串)
-- ========================================================================================================================================================================
local prev = redis.call('GET', idemKey)
if prev then
    -- 如果查到了记录，说明这个 requestId 之前已经成功扣减过；pcall 相当于 try-catch，防止 JSON 解析报错导致脚本崩溃
    local ok, obj = pcall(cjson.decode, prev)
    if ok and type(obj) == 'table' then
        obj.idempotent = true     -- 加上 idempotent=true 标记,Java 侧能区分"真扣"和"重放"；不在这里做字符串拼接,直接 decode → 加字段 → encode
        return cjson.encode(obj)
    end
    return prev    -- 如果解析失败，把原内容丢回去兜底
end




-- ========================================================================================================================================================================
-- Step 2. 业务规则校验 (兜底校验)，活动是否结束
-- ========================================================================================================================================================================
if nowMs >= activityEndMs then
    return cjson.encode({code=-3, msg="activity ended", ts=nowMs})
end

-- SKU 是否初始化(运营忘了上架的常见 bug)
local stockRaw = redis.call('GET', stockKey)
if stockRaw == false then
    -- 返回 false 说明 key 压根没初始化，可能是运营忘配置活动库存了
    return cjson.encode({code=-1, msg="sku not initialized", ts=nowMs})
end
local stock = tonumber(stockRaw)
if not stock then
    return cjson.encode({code=-9, msg="sku stock corrupted", ts=nowMs})
end


-- ========================================================================================================================================================================
-- Step 3. 用户级限购校验 (很多 case 漏掉这一层,只看总库存,黑产羊毛党刷穿)
-- ========================================================================================================================================================================
local purchasedRaw = redis.call('HGET', userPurKey, userId) -- 从 Hash 中获取该用户已经买过的数量 (类似 map.get(userId))
local purchased = tonumber(purchasedRaw) or 0  -- 如果是新用户没买过，HGET 会返回 nil，此时 "or 0" 起到默认值的作用
if purchased + qty > userMaxLimit then
    -- 拒绝扣减：加上这次买的，超过了单人最大限制
    return cjson.encode({
        code=-2, msg="user limit exceeded",
        userPurchased=purchased, userLimitLeft=userMaxLimit-purchased,
        qty=qty, ts=nowMs
    })
end



-- ========================================================================================================================================================================
-- Step 4. 总库存校验
-- ========================================================================================================================================================================
if stock < qty then
    -- 返回 code=0 交给 Java 端处理 (前端一般会显示"已抢光")
    return cjson.encode({code=0, msg="out of stock", remain=stock, qty=qty, ts=nowMs})
end


-- ========================================================================================================================================================================
-- Step 5. 核心事务写操作 (绝对安全的原子操作)，扣库存 + 累加用户已购 + 生成 txId + 写流水
-- ========================================================================================================================================================================
local remain = redis.call('DECRBY', stockKey, qty)                          -- 1. 扣总库存
local newPurchased = redis.call('HINCRBY', userPurKey, userId, qty)         -- 2. 累加用户的已购数量
local seq = redis.call('INCR', txCounter)                                   -- 3. 利用自增器获取当前流水序号
local txId = string.format("tx-%s-%010d", requestId, seq)    -- 4. 拼装出全局唯一的流水号 txId (例如: tx-req888-0000012345)

-- 5. 记录异步落库用的流水记录 (LPUSH 塞进 List 头部  + LTRIM 双手段保证不无限膨胀)
local txRecord = cjson.encode({
    txId=txId, userId=userId, qty=qty, remain=remain,
    purchased=newPurchased, ts=nowMs, requestId=requestId
})
redis.call('LPUSH', txStream, txRecord)
redis.call('LTRIM', txStream, 0, streamMaxLen - 1) --【高阶技巧】LTRIM 修剪流水 List，保证它永远只保留最新的 streamMaxLen 条，防止撑爆 Redis 内存


-- ========================================================================================================================================================================
-- Step 6. 构造返回值 & 固化幂等状态，把"业务结果"自身写入幂等键 → 重放可直接拿上次结果
-- ========================================================================================================================================================================
local result = cjson.encode({
    code=1, msg="ok", txId=txId,
    qty=qty, remain=remain,
    userPurchased=newPurchased,
    userLimitLeft= userMaxLimit-newPurchased,
    ts=nowMs, idempotent=false
})
redis.call('SET', idemKey, result, 'EX', idemTtl) -- 将成功的"业务结果"原封不动地存入幂等 Key 中，并设置过期时间，下次如果同一个 requestId 重复请求，在 Step 1 就会直接拦截并原样返回这个 JSON
return result
