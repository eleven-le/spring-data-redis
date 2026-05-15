package org.springframework.data.redis.laboratory.l4.l4_08.scenario;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.L408Keys;
import org.springframework.data.redis.laboratory.l4.l4_08.transaction.L408Transactions;
import org.springframework.data.redis.laboratory.l4.l4_08.watch.L408OptimisticRetryTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.watch.L408OptimisticRetryTemplate.OptimisticResult;

import java.time.Duration;
import java.util.List;

/**
 * 商品库存扣减（事务版本）。
 * <p>
 * <b>用途定位</b>：本类是"事务版本 baseline"，用于和 Lua 版本作对比。
 * <ul>
 *   <li>低/中并发：WATCH + DECRBY 可工作；</li>
 *   <li>秒杀级高并发：冲突率会迅速上升，重试代价巨大，<b>请改用 Lua</b>（详见 L4-10）；</li>
 *   <li>无论选哪个，库存事实源仍要 DB / MQ 兜底，避免 Redis 主从切换导致超卖。</li>
 * </ul>
 */
public class L408StockDeductTransactionScenario {

    private final StringRedisTemplate template;
    private final L408OptimisticRetryTemplate retryTemplate = new L408OptimisticRetryTemplate();

    public L408StockDeductTransactionScenario(StringRedisTemplate template) {
        this.template = template;
    }

    public L408OptimisticRetryTemplate.RetryMetrics getMetrics() {
        return retryTemplate.getMetrics();
    }

    public void initStock(String skuId, long stock) {
        template.opsForValue().set(L408Keys.stockSku(skuId), String.valueOf(stock));
    }

    /**
     * 单次尝试：成功返回 EXEC 结果（含新库存），冲突返回 null，库存不足返回 emptyList。
     */
    public List<Object> deductByWatchTransaction(String skuId, long quantity) {
        String stockKey = L408Keys.stockSku(skuId);
        return L408Transactions.runTxString(template, ops -> {
            ops.watch(stockKey);
            String raw = ops.opsForValue().get(stockKey);
            long stock = raw == null ? 0L : Long.parseLong(raw);
            if (stock < quantity) {
                ops.unwatch();
                return List.of();
            }
            ops.multi();
            ops.opsForValue().increment(stockKey, -quantity);
            return ops.exec();
        });
    }

    /**
     * 带退避有限重试。
     */
    public OptimisticResult<List<Object>> deductByWatchTransactionWithRetry(String skuId,
                                                                            long quantity,
                                                                            int maxRetries) {
        return retryTemplate.executeWithBackoff(maxRetries, Duration.ofMillis(10), () -> {
            List<Object> r = deductByWatchTransaction(skuId, quantity);
            if (r == null) return null;
            if (r.isEmpty()) return List.of("__OUT_OF_STOCK__");
            return r;
        });
    }

    public Long getStock(String skuId) {
        String raw = template.opsForValue().get(L408Keys.stockSku(skuId));
        return raw == null ? null : Long.parseLong(raw);
    }

    public void clearStock(String skuId) {
        template.delete(L408Keys.stockSku(skuId));
    }
}
