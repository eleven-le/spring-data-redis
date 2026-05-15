package org.springframework.data.redis.laboratory.l4.l4_10.compare;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.laboratory.l4.l4_10.L410Keys;
import org.springframework.data.redis.laboratory.l4.l4_10.result.StockDeductResult;
import org.springframework.data.redis.laboratory.l4.l4_10.scenario.L410StockDeductLuaScenario;

import java.util.List;
import java.util.UUID;

/**
 * 对比 2：Lua vs Pipeline。
 * <p>
 * <b>结论先行</b>：
 * <ul>
 *   <li>Pipeline 适合<b>批量无依赖命令</b>，目的是减 RTT，<i>不保证原子</i>；</li>
 *   <li>Lua 适合<b>读+判断+写的复合原子逻辑</b>，目的是<i>原子</i>，附带减 RTT；</li>
 * </ul>
 * 两者解决的是不同问题。把 Pipeline 当事务用，是踩雷概率最高的反模式之一。
 */
public class L410LuaVsPipelineCompare {

    private final StringRedisTemplate template;
    private final L410StockDeductLuaScenario stockScenario;

    public L410LuaVsPipelineCompare(StringRedisTemplate template,
                                    RedisScript<String> stockDeductScript,
                                    ObjectMapper mapper) {
        this.template = template;
        this.stockScenario = new L410StockDeductLuaScenario(template, stockDeductScript, mapper);
    }

    /**
     * 反例:Pipeline 不保证"判断 → 扣减"两步之间不被插队。
     * 高并发下两个 Pipeline 都看到 stock=1,都通过判断、都 DECR,超卖。
     */
    public void pipelineCannotGuaranteeAtomicDecision(String skuId) {
        String key = L410Keys.stock(skuId);
        try {
            template.executePipelined((RedisCallback<Object>) connection -> {
                byte[] k = key.getBytes();
                connection.stringCommands().get(k);     // 命令 1：拿当前值
                connection.stringCommands().decr(k);    // 命令 2：直接扣减（不判断）
                return null;
            });
        } catch (InvalidDataAccessApiUsageException e) {
            throw e;
        }
        // 即便我们在 Pipeline 中拿到了 stock 的值，也无法用它的结果"决定"是否要 DECR——
        // Pipeline 回调里没法读前一条结果，最多只能在 closePipeline 之后看到 List<Object>。
    }

    /** 正例:把判断 + 扣减打到 Lua 里原子完成,并返回结构化流水。 */
    public StockDeductResult luaCanReadCheckWriteAtomically(String skuId, String userId) {
        long activityEnd = System.currentTimeMillis() + 600_000L;
        return stockScenario.deduct(skuId, userId, 1, 999_999L,
                UUID.randomUUID().toString(), activityEnd);
    }

    /** Pipeline 真正擅长的：批量无依赖命令，比如批量预热缓存。 */
    public List<Object> pipelineGoodAtBatchCommands(List<String> keys, String value) {
        return template.executePipelined((RedisCallback<Object>) connection -> {
            for (String k : keys) {
                connection.stringCommands().set(k.getBytes(), value.getBytes());
            }
            return null;
        });
    }
}
