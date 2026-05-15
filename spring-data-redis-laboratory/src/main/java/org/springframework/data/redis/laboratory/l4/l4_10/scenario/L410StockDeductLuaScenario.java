package org.springframework.data.redis.laboratory.l4.l4_10.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.laboratory.l4.l4_10.L410Keys;
import org.springframework.data.redis.laboratory.l4.l4_10.result.StockDeductResult;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 生产级·秒杀库存扣减(含 用户限购 + requestId 幂等 + 扣减流水)。
 * <p>
 * 真实场景:茶饮限量抢购"前 1000 杯 1 元",单用户每活动最多 2 杯。
 * 脚本一次性把"幂等门 + 用户限购 + 总库存 + 流水"一起原子化处理,Java 侧拿到的是结构化结果,
 * 不再是裸 1/0,可以直接驱动:
 * <ul>
 *   <li>前端弹"恭喜你抢到 + 扣减后还剩 X 杯 + 流水号 tx-xxx"</li>
 *   <li>异步 MQ 把 txId 抛到下游做 DB 落账</li>
 *   <li>上游 retry/MQ 重投,Lua 内部识别幂等返回上次结果(idempotent=true)</li>
 *   <li>风控:userLimitLeft<=0 时拉黑或弹"已达本人上限"</li>
 * </ul>
 *
 * <h3>断点路径</h3>
 * <ol>
 *   <li>{@code RedisTemplate#execute(RedisScript, List, Object...)}</li>
 *   <li>{@code DefaultScriptExecutor#execute} —— 看 evalSha + NOSCRIPT fallback eval</li>
 *   <li>{@code LettuceConnection#scriptingCommands} → {@code evalSha}</li>
 *   <li>{@code DefaultRedisScript#getSha1} —— 懒加载双重检查</li>
 * </ol>
 *
 * <h3>Redis 不是事实源</h3>
 * Redis 扣减成功后,应当把 txId 投到 MQ,下游 consume 后写入 DB(对账以 DB 为准)。
 */
public class L410StockDeductLuaScenario {

    private final StringRedisTemplate template;
    private final RedisScript<String> script;
    private final ObjectMapper mapper;

    public L410StockDeductLuaScenario(StringRedisTemplate template,
                                      RedisScript<String> stockDeductScript,
                                      ObjectMapper mapper) {
        this.template = template;
        this.script = stockDeductScript;
        this.mapper = mapper;
    }

    public void initStock(String skuId, long stock) {
        //l4:10:stock:sku:{skuId}
        template.opsForValue().set(L410Keys.stock(skuId), String.valueOf(stock));
    }

    /**
     * 默认 idemTtl=24h、流水保留 5000 条、用户限购为 caller 给定。
     *
     * @param requestId 幂等 token,客户端生成的 uuid;同一 requestId 第二次进来直接返回上次结果
     */
    public StockDeductResult deduct(String skuId, String userId, long qty,
                                    long userMaxLimit, String requestId,
                                    long activityEndMs) {
        long now = System.currentTimeMillis();
        List<String> keys = Arrays.asList(
                L410Keys.stock(skuId),//l4:10:stock:sku:{skuId}
                L410Keys.userPurchased(skuId),//l4:10:user_purchased:{skuId}
                L410Keys.stockTx(skuId),//l4:10:stock:tx:{skuId}
                L410Keys.stockIdem(skuId, requestId), //l4:10:idem:stock:{skuId}:requestId
                L410Keys.stockTxId(skuId) //l4:10:txid:{matcha-2026}
        );
        String json = template.execute(
                script, keys,
                String.valueOf(qty), //购买数量
                userId,
                String.valueOf(userMaxLimit), //用户限购数量
                requestId, //请求id
                String.valueOf(now), //当前时间
                String.valueOf(activityEndMs), //活动结束时间
                String.valueOf(24 * 3600),     // idemTtl 24h
                String.valueOf(5000)            // 流水最多保留 5000 条
        );
        try {
            return mapper.readValue(json, StockDeductResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Lua 返回 JSON 解析失败:" + json, e);
        }
    }

    /**
     * 便捷重载:requestId 自动生成。
     */
    public StockDeductResult deductOnce(String skuId, String userId, long qty,
                                        long userMaxLimit, long activityEndMs) {
        return deduct(skuId, userId, qty, userMaxLimit, UUID.randomUUID().toString(), activityEndMs);
    }

    public Long getStock(String skuId) {
        String val = template.opsForValue().get(L410Keys.stock(skuId));
        return val == null ? null : Long.parseLong(val);
    }

    public Long getUserPurchased(String skuId, String userId) {
        Object v = template.opsForHash().get(L410Keys.userPurchased(skuId), userId);
        return v == null ? 0L : Long.parseLong(v.toString());
    }

    public List<String> tailTx(String skuId, long n) {
        return template.opsForList().range(L410Keys.stockTx(skuId), 0, n - 1);
    }

    public void clearAll(String skuId) {
        template.delete(Arrays.asList(
                L410Keys.stock(skuId),//l4:10:stock:sku:{skuId}
                L410Keys.userPurchased(skuId),//l4:10:user_purchased:{skuId}
                L410Keys.stockTx(skuId),//l4:10:stock:tx:{skuId}
                L410Keys.stockTxId(skuId) //l4:10:txid:{skuId}
        ));
    }
}
