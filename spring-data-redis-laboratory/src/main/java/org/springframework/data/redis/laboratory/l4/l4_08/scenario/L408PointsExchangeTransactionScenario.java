package org.springframework.data.redis.laboratory.l4.l4_08.scenario;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.L408Keys;
import org.springframework.data.redis.laboratory.l4.l4_08.transaction.L408Transactions;
import org.springframework.data.redis.laboratory.l4.l4_08.watch.L408OptimisticRetryTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.watch.L408OptimisticRetryTemplate.OptimisticResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 会员积分扣减 + 创建兑换订单（事务版本）。
 * <p>
 * 标准 WATCH + GET + 校验 + MULTI + DECRBY + HSET + EXEC 模板。
 * 失败由 {@link L408OptimisticRetryTemplate} 做有限退避重试。
 * <p>
 * <b>强调</b>：本场景中 Redis 只是性能层，订单的最终事实源仍应是关系型数据库；
 * 高并发下要做"前置令牌桶 + 有限重试 + 异步 DB 落库"。Redis 事务无回滚。
 */
public class L408PointsExchangeTransactionScenario {

    private final StringRedisTemplate template;
    private final L408OptimisticRetryTemplate retryTemplate = new L408OptimisticRetryTemplate();

    public L408PointsExchangeTransactionScenario(StringRedisTemplate template) {
        this.template = template;
    }

    public L408OptimisticRetryTemplate.RetryMetrics getMetrics() {
        return retryTemplate.getMetrics();
    }

    public void initUserPoints(String userId, long points) {
        template.opsForValue().set(L408Keys.pointsUser(userId), String.valueOf(points));
    }

    /**
     * 单次尝试：成功返回 EXEC 结果列表，冲突返回 null。
     */
    public List<Object> exchange(String userId, String orderId, long costPoints) {
        String pointsKey = L408Keys.pointsUser(userId);
        String orderKey = L408Keys.exchangeOrder(orderId);

        return L408Transactions.runTxString(template, ops -> {
            ops.watch(pointsKey);
            String currentRaw = ops.opsForValue().get(pointsKey);
            long current = currentRaw == null ? 0 : Long.parseLong(currentRaw);
            if (current < costPoints) {
                ops.unwatch();
                // 业务前置失败，不算冲突，约定返回 emptyList
                return List.of();
            }
            ops.multi();
            ops.opsForValue().increment(pointsKey, -costPoints);
            ops.opsForHash().putAll(orderKey, Map.of(
                    "userId", userId,
                    "orderId", orderId,
                    "cost", String.valueOf(costPoints),
                    "createdAt", String.valueOf(System.currentTimeMillis())
            ));
            ops.expire(orderKey, Duration.ofDays(7));
            return ops.exec();
        });
    }

    /**
     * 带有限重试 + 退避的兑换。返回最终结果。
     */
    public OptimisticResult<List<Object>> exchangeWithRetry(String userId,
                                                             String orderId,
                                                             long costPoints,
                                                             int maxRetries) {
        return retryTemplate.executeWithBackoff(maxRetries, Duration.ofMillis(10), () -> {
            List<Object> r = exchange(userId, orderId, costPoints);
            // 与 OptimisticAction 约定：null 表示重试，emptyList 表示业务前置失败（不重试）
            if (r == null) return null;
            if (r.isEmpty()) {
                // 用一个特殊 sentinel 让 retryTemplate 视作"成功（业务拒绝）"
                return List.of("__BUSINESS_REJECTED__");
            }
            return r;
        });
    }

    public Long getUserPoints(String userId) {
        String raw = template.opsForValue().get(L408Keys.pointsUser(userId));
        return raw == null ? null : Long.parseLong(raw);
    }

    public Map<Object, Object> getExchangeOrder(String orderId) {
        return template.opsForHash().entries(L408Keys.exchangeOrder(orderId));
    }

    public void clearData(String userId, String orderId) {
        template.delete(L408Keys.pointsUser(userId));
        template.delete(L408Keys.exchangeOrder(orderId));
    }
}
