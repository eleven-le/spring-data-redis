package org.springframework.data.redis.laboratory.l4.l4_10.compare;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.laboratory.l4.l4_10.result.StockDeductResult;
import org.springframework.data.redis.laboratory.l4.l4_10.scenario.L410StockDeductLuaScenario;

import java.util.UUID;

/**
 * 对比 4:Lua vs MQ。
 * <p>
 * Lua = 同步链路快速原子决策;MQ = 异步可靠投递 + 削峰 + 解耦。
 * 真实秒杀:Lua 决策 → 发 MQ → 异步落 DB(对账以 DB 为准)。
 */
public class L410LuaVsMqCompare {

    private final L410StockDeductLuaScenario stockScenario;

    public L410LuaVsMqCompare(StringRedisTemplate template,
                              RedisScript<String> stockDeductScript,
                              ObjectMapper mapper) {
        this.stockScenario = new L410StockDeductLuaScenario(template, stockDeductScript, mapper);
    }

    public StockDeductResult luaFastDecisionPlaceholder(String skuId, String userId, long activityEndMs) {
        return stockScenario.deduct(skuId, userId, 1, 999_999L,
                UUID.randomUUID().toString(), activityEndMs);
    }

    public void mqReliableAsyncPlaceholder(String txId) {
        // 真实业务:rocketmqTemplate.syncSend("ORDER_DEDUCT_TOPIC", txId);
        System.out.println("[MQ-placeholder] async send order_deduct event for txId=" + txId);
    }
}
