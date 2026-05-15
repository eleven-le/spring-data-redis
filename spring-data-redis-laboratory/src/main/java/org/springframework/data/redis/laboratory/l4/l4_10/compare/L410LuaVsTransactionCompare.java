package org.springframework.data.redis.laboratory.l4.l4_10.compare;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.laboratory.l4.l4_10.result.StockDeductResult;
import org.springframework.data.redis.laboratory.l4.l4_10.scenario.L410StockDeductLuaScenario;

import java.util.UUID;

/**
 * 对比 3:Lua vs MULTI/EXEC(Transaction)。
 * <p>
 * 共同点:服务端原子执行。
 * 差异:
 * <ul>
 *   <li>MULTI/EXEC 命令间不能互相决策,只能配 WATCH 实现 CAS;</li>
 *   <li>Lua 在服务端单线程内一体执行,可以读+判断+写,决策能力强于 MULTI/EXEC;</li>
 *   <li>都<b>不会回滚已执行的写</b>,这是 Redis 事务和 DB 事务的根本差异。</li>
 * </ul>
 * 完整事务讲解见 L4-08。
 */
public class L410LuaVsTransactionCompare {

    private final L410StockDeductLuaScenario stockScenario;

    public L410LuaVsTransactionCompare(StringRedisTemplate template,
                                       RedisScript<String> stockDeductScript,
                                       ObjectMapper mapper) {
        this.stockScenario = new L410StockDeductLuaScenario(template, stockDeductScript, mapper);
    }

    public void transactionPlaceholder() {
        System.out.println("[transactionPlaceholder] 见 L4-08:MULTI/EXEC/WATCH/SessionCallback。"
                + "Lua 已能覆盖大多数原子复合逻辑,事务通常只在 WATCH+CAS 场景需要。");
    }

    public StockDeductResult luaAtomicScriptExample(String skuId, String userId, long activityEndMs) {
        return stockScenario.deduct(skuId, userId, 1, 999_999L,
                UUID.randomUUID().toString(), activityEndMs);
    }
}
