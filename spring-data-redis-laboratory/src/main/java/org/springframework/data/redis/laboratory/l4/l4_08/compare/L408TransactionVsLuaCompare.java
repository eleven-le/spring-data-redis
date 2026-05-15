package org.springframework.data.redis.laboratory.l4.l4_08.compare;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.scenario.L408StockDeductTransactionScenario;

/**
 * Redis 事务 vs Lua 对比。
 * <p>
 * 结论：
 * <ul>
 *   <li>低/中并发"读判断写"：WATCH + MULTI/EXEC 可以工作；</li>
 *   <li>高并发"读判断写"：Lua 更适合，server 侧单脚本原子，无需重试；</li>
 *   <li>Lua 的具体实现见 L4-10。本类只做对比与占位。</li>
 * </ul>
 */
public class L408TransactionVsLuaCompare {

    private final StringRedisTemplate template;

    public L408TransactionVsLuaCompare(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * 事务版库存扣减（基线）。
     */
    public Object watchTransactionStockDeduct(String skuId, long quantity, int maxRetries) {
        L408StockDeductTransactionScenario sc = new L408StockDeductTransactionScenario(template);
        return sc.deductByWatchTransactionWithRetry(skuId, quantity, maxRetries);
    }

    /**
     * Lua 版占位：真实代码请参考 L4-10。
     * <p>
     * 这里给一段思路 + Lua 文本，便于一眼看到与事务的差异。
     */
    public void luaStockDeductPlaceholder() {
        System.out.println("=== Lua 版库存扣减（伪代码占位，真实实现见 L4-10）===");
        System.out.println("local stock = tonumber(redis.call('GET', KEYS[1]) or '0')");
        System.out.println("if stock < tonumber(ARGV[1]) then return -1 end");
        System.out.println("return redis.call('DECRBY', KEYS[1], ARGV[1])");
        System.out.println("------");
        System.out.println("- 单脚本原子，无 WATCH 重试；");
        System.out.println("- 服务端单线程执行，命令间无穿插；");
        System.out.println("- 失败语义清晰（返回 -1 或抛 SCRIPT ERR）。");
    }
}
