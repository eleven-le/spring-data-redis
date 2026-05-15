package org.springframework.data.redis.laboratory.l4.l4_03.hash;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_03.L403Keys;

import java.time.Duration;
import java.util.Map;

/**
 * 购物车场景：
 * <pre>
 *   key   = l4:03:cart:{userId}
 *   field = skuId
 *   value = 数量（数字字符串）
 * </pre>
 * <p>
 * 为什么 Hash 适合：
 * <ul>
 *   <li>添加/删除一个 SKU = HSET / HDEL 一个 field，命令复杂度 O(1)；</li>
 *   <li>数量增减 = HINCRBY，服务端原子，不会丢数；</li>
 *   <li>查整车 = HGETALL（控制 SKU 数量在合理范围内）。</li>
 * </ul>
 * <p>
 * 重要避坑：
 * <ul>
 *   <li>Hash TTL 只有整 key 级别。不能给 "skuId=A001" 这一个 field 单独设过期。
 *       想做"商品被加入购物车 30 天后自动清理"必须用其他设计（独立 ZSet 记录加入时间，定时清理）。</li>
 *   <li>合并未登录购物车要"多端冲突合并"策略：相同 SKU 数量取大 / 取和 / 业务自定义，不要简单覆盖。</li>
 * </ul>
 */
public class L403ShoppingCartHashScenario {

    public static final Duration CART_TTL = Duration.ofDays(30);

    private final HashOperations<String, String, Object> ops;
    private final RedisTemplate<String, Object> template;

    public L403ShoppingCartHashScenario(RedisTemplate<String, Object> template) {
        this.template = template;
        this.ops = template.opsForHash();
    }

    private static String key(String userId) {
        return L403Keys.CART + userId;
    }

    public void addSku(String userId, String skuId, long quantity) {
        String k = key(userId);
        ops.put(k, skuId, quantity);
        template.expire(k, CART_TTL);
    }

    /**
     * 数量加减用 HINCRBY，服务端原子。
     */
    public Long increaseSku(String userId, String skuId, long delta) {
        return ops.increment(key(userId), skuId, delta);
    }

    public Long decreaseSku(String userId, String skuId, long delta) {
        return ops.increment(key(userId), skuId, -delta);
    }

    public void removeSku(String userId, String skuId) {
        ops.delete(key(userId), skuId);
    }

    public Map<String, Object> getCart(String userId) {
        return template.<String, Object>opsForHash().entries(key(userId));
    }

    public void clearCart(String userId) {
        template.delete(key(userId));
    }

    /**
     * 合并游客购物车到登录购物车：相同 SKU 数量"取和"。
     * 真实业务通常还要叠加：库存校验、上架状态、活动价校对、合并上限等。
     */
    public void mergeGuestCartToUserCart(String guestId, String userId) {
        Map<String, Object> guestCart = template.<String, Object>opsForHash().entries(key(guestId));
        if (guestCart.isEmpty()) return;

        for (Map.Entry<String, Object> e : guestCart.entrySet()) {
            long qty = parseLong(e.getValue());
            // 多 field 累加：循环 HINCRBY；想批量原子可改 Lua 脚本
            ops.increment(key(userId), e.getKey(), qty);
        }
        template.expire(key(userId), CART_TTL);
        template.delete(key(guestId));
    }

    private static long parseLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        return Long.parseLong(v.toString());
    }
}
