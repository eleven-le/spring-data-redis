package org.springframework.data.redis.laboratory.l4.l4_03.hash;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_03.L403Keys;

import java.util.Map;

/**
 * 商品多规格 SKU 库存"展示缓存"场景：
 * <pre>
 *   key   = l4:03:sku:stock:{itemId}
 *   field = skuId
 *   value = 展示库存数
 * </pre>
 * <p>
 * 重要边界：这是 <b>展示库存</b>，不是真实库存。
 * <ul>
 *   <li>真实业务严禁仅靠 Redis HINCRBY 做扣减来防超卖：
 *       多机房副本、缓存重建、Redis 故障都能造成超卖；</li>
 *   <li>真正防超卖至少需要：Lua 脚本（HGET + 比较 + HINCRBY 在服务端原子）+
 *       异步同步 DB（库存中心）+ 兜底重对账；</li>
 *   <li>Redis 在这一层只是高性能读写层，不是最终事实源。</li>
 * </ul>
 * 这里的接口名字带 ForDisplay，提醒调用方 —— 真扣库存请走库存中心。
 */
public class L403SkuStockHashScenario {

    private final HashOperations<String, String, Object> ops;
    private final RedisTemplate<String, Object> template;

    public L403SkuStockHashScenario(RedisTemplate<String, Object> template) {
        this.template = template;
        this.ops = template.opsForHash();
    }

    private static String key(String itemId) {
        return L403Keys.SKU_STOCK + itemId;
    }

    /**
     * 初始化某商品的所有 SKU 展示库存，一次 HMSET。
     */
    public void initSkuStock(String itemId, Map<String, Long> skuStockMap) {
        ops.putAll(key(itemId), skuStockMap);
    }

    public Object getSkuStock(String itemId, String skuId) {
        return ops.get(key(itemId), skuId);
    }

    /**
     * 展示扣减。原子，但不能保证"展示库存 == 真实库存"——
     * 这只是给 C 端用户看的数字，DB / 库存中心才是真相。
     */
    public Long decreaseSkuStockForDisplay(String itemId, String skuId, long delta) {
        return ops.increment(key(itemId), skuId, -delta);
    }

    public Long increaseSkuStockForDisplay(String itemId, String skuId, long delta) {
        return ops.increment(key(itemId), skuId, delta);
    }

    public Map<String, Object> getAllSkuStock(String itemId) {
        return template.<String, Object>opsForHash().entries(key(itemId));
    }
}
