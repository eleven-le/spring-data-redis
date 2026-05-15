package org.springframework.data.redis.laboratory.l4.l4_08.scenario;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.L408Keys;
import org.springframework.data.redis.laboratory.l4.l4_08.transaction.L408Transactions;
import org.springframework.data.redis.laboratory.l4.l4_08.watch.L408OptimisticRetryTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.watch.L408OptimisticRetryTemplate.OptimisticResult;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * 优惠券领取（事务版本，多 key WATCH）。
 * <p>
 * Key 设计（Cluster hash tag 友好）：
 * <pre>
 *   l4:08:coupon:{actId}:stock     —— 剩余库存
 *   l4:08:coupon:{actId}:users     —— 已领用户集合
 * </pre>
 * <p>
 * 算法：
 * <ol>
 *   <li>WATCH 两个 key；</li>
 *   <li>SISMEMBER users uid → 已领则 unwatch + 退出；</li>
 *   <li>GET stock → ≤0 则 unwatch + 退出；</li>
 *   <li>MULTI；</li>
 *   <li>DECRBY stock；</li>
 *   <li>SADD users uid；</li>
 *   <li>EXEC；冲突有限重试。</li>
 * </ol>
 * <p>
 * 高并发秒杀场景仍优先 Lua，本类作为事务模板教学样本。
 */
public class L408CouponClaimTransactionScenario {

    private final StringRedisTemplate template;
    private final L408OptimisticRetryTemplate retryTemplate = new L408OptimisticRetryTemplate();

    public L408CouponClaimTransactionScenario(StringRedisTemplate template) {
        this.template = template;
    }

    public L408OptimisticRetryTemplate.RetryMetrics getMetrics() {
        return retryTemplate.getMetrics();
    }

    public void initCouponStock(String activityId, long stock) {
        template.opsForValue().set(L408Keys.couponStock(activityId), String.valueOf(stock));
        template.delete(L408Keys.couponUsers(activityId));
    }

    public List<Object> claimCoupon(String activityId, String userId) {
        String stockKey = L408Keys.couponStock(activityId);
        String usersKey = L408Keys.couponUsers(activityId);

        return L408Transactions.runTxString(template, ops -> {
            ops.watch(Arrays.asList(stockKey, usersKey));

            Boolean already = ops.opsForSet().isMember(usersKey, userId);
            if (Boolean.TRUE.equals(already)) {
                ops.unwatch();
                return List.of("__ALREADY_CLAIMED__");
            }

            String raw = ops.opsForValue().get(stockKey);
            long stock = raw == null ? 0L : Long.parseLong(raw);
            if (stock <= 0) {
                ops.unwatch();
                return List.of("__OUT_OF_STOCK__");
            }

            ops.multi();
            ops.opsForValue().increment(stockKey, -1);
            ops.opsForSet().add(usersKey, userId);
            return ops.exec();
        });
    }

    public OptimisticResult<List<Object>> claimCouponWithRetry(String activityId,
                                                               String userId,
                                                               int maxRetries) {
        return retryTemplate.executeWithBackoff(maxRetries, Duration.ofMillis(10), () -> {
            List<Object> r = claimCoupon(activityId, userId);
            if (r == null) return null;
            return r; // emptyList 不会出现，前置失败用 sentinel string 返回
        });
    }

    public boolean hasClaimed(String activityId, String userId) {
        return Boolean.TRUE.equals(
                template.opsForSet().isMember(L408Keys.couponUsers(activityId), userId));
    }

    public Long getRemainStock(String activityId) {
        String raw = template.opsForValue().get(L408Keys.couponStock(activityId));
        return raw == null ? null : Long.parseLong(raw);
    }

    public void clearCoupon(String activityId) {
        template.delete(L408Keys.couponStock(activityId));
        template.delete(L408Keys.couponUsers(activityId));
    }
}
