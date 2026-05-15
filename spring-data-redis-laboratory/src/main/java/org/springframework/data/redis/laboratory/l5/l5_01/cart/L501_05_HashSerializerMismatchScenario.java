package org.springframework.data.redis.laboratory.l5.l5_01.cart;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.laboratory.l5.l5_01.L501Keys;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 真实场景：购物车 Hash 结构缓存。
 * <p>
 * Redis 结构：
 * <pre>
 *   cart:{userId}
 *     sku:{skuId} -> CartItemSnapshot
 * </pre>
 * 对应文档：L5-01 → §4.4 Hash 结构 / §5 第 9 坑：HGET 查不到。
 * <p>
 * 这一节里我们手动构造两个"看起来一样、其实序列化策略不同"的 RedisTemplate：
 * <ul>
 *   <li>{@link #correctTemplate}：hashKey 用 StringRedisSerializer，hashValue 用 JSON。</li>
 *   <li>{@link #wrongTemplate}：hashKey 不小心用了 JdkSerializationRedisSerializer。</li>
 * </ul>
 * 然后演示：correctTemplate 写入 → wrongTemplate 读取，HGET 查不到。
 * <p>
 * 这就是线上最容易把"数据丢了"的伪事故定性为真事故的场景之一：
 * 数据其实在 Redis 里，只是 hashKey 序列化后的字节不一样，HGET 命中不到字段。
 * <p>
 * 运行前：本地 Redis 可达；不要和真实业务库混用 database。
 * 运行后观察：
 * <pre>
 *   redis-cli HKEYS l5:01:cart:u-1001
 *   # 正确：sku:100086、sku:200001 （人类可读）
 *   # 错误：\xAC\xED\x00\x05t\x00\nsku:100086... （二进制乱码）
 * </pre>
 * 学到什么：
 * 1) hashKey 的字节才是真正的"列名"。客户端只要序列化策略不一致，HGET 一律 miss；
 * 2) 排查这种问题第一招是 {@code OBJECT ENCODING} + {@code HKEYS}，看裸字节比看代码快；
 * 3) Spring Data Redis 给了"四把独立序列化器"的灵活，但灵活也意味着"四个地方都得对得上"。
 */
public class L501_05_HashSerializerMismatchScenario {

    private final RedisTemplate<String, Object> correctTemplate;
    private final RedisTemplate<String, Object> wrongTemplate;

    public L501_05_HashSerializerMismatchScenario(RedisConnectionFactory factory) {
        this.correctTemplate = buildTemplate(factory, new StringRedisSerializer(),
                new GenericJackson2JsonRedisSerializer());
        // 故意让 hashKey 走 JDK 序列化——这是真实事故现场最常见的错配
        this.wrongTemplate = buildTemplate(factory, new JdkSerializationRedisSerializer(),
                new GenericJackson2JsonRedisSerializer());
    }

    private static RedisTemplate<String, Object> buildTemplate(RedisConnectionFactory factory,
                                                               Object hashKeySerializer,
                                                               Object hashValueSerializer) {
        RedisTemplate<String, Object> t = new RedisTemplate<>();
        t.setConnectionFactory(factory);
        StringRedisSerializer string = new StringRedisSerializer();
        t.setKeySerializer(string);
        t.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        t.setHashKeySerializer((org.springframework.data.redis.serializer.RedisSerializer<?>) hashKeySerializer);
        t.setHashValueSerializer((org.springframework.data.redis.serializer.RedisSerializer<?>) hashValueSerializer);
        t.afterPropertiesSet();
        return t;
    }

    private static String cartKey(String userId)  { return L501Keys.CART + userId; }
    private static String fieldKey(String skuId)  { return "sku:" + skuId; }

    /** 正确策略写入。 */
    public void putItem(String userId, CartItemSnapshot item) {
        HashOperations<String, Object, Object> ops = correctTemplate.opsForHash();
        ops.put(cartKey(userId), fieldKey(item.getSkuId()), item);
    }

    /** 正确策略读取。 */
    public CartItemSnapshot getItem(String userId, String skuId) {
        HashOperations<String, Object, Object> ops = correctTemplate.opsForHash();
        return (CartItemSnapshot) ops.get(cartKey(userId), fieldKey(skuId));
    }

    /** 错误策略读取——演示 "HGET 查不到" 的伪事故。 */
    public Object getItemWithWrongSerializer(String userId, String skuId) {
        HashOperations<String, Object, Object> ops = wrongTemplate.opsForHash();
        return ops.get(cartKey(userId), fieldKey(skuId));
    }

    /** 完整查询本 userId 的购物车，用正确策略。 */
    public Map<Object, Object> dumpCart(String userId) {
        return correctTemplate.opsForHash().entries(cartKey(userId));
    }

    public void evict(String userId) {
        correctTemplate.delete(cartKey(userId));
    }

    /** 购物车 Item：贴近 C 端真实业务字段。 */
    public static class CartItemSnapshot implements Serializable {
        private static final long serialVersionUID = 1L;

        private String skuId;
        private String skuName;
        private BigDecimal unitPrice;
        private int quantity;
        private boolean selected;
        private long addedAt;

        public CartItemSnapshot() {}

        public CartItemSnapshot(String skuId, String skuName, BigDecimal unitPrice,
                                int quantity, boolean selected, long addedAt) {
            this.skuId = skuId; this.skuName = skuName;
            this.unitPrice = unitPrice; this.quantity = quantity;
            this.selected = selected; this.addedAt = addedAt;
        }

        public String getSkuId()         { return skuId; }
        public String getSkuName()       { return skuName; }
        public BigDecimal getUnitPrice() { return unitPrice; }
        public int getQuantity()         { return quantity; }
        public boolean isSelected()      { return selected; }
        public long getAddedAt()         { return addedAt; }

        public void setSkuId(String s)         { this.skuId = s; }
        public void setSkuName(String s)       { this.skuName = s; }
        public void setUnitPrice(BigDecimal p) { this.unitPrice = p; }
        public void setQuantity(int q)         { this.quantity = q; }
        public void setSelected(boolean b)     { this.selected = b; }
        public void setAddedAt(long t)         { this.addedAt = t; }

        @Override
        public String toString() {
            return "CartItemSnapshot{" + skuId + "," + skuName + ",×" + quantity +
                    ",单价=" + unitPrice + ",selected=" + selected + "}";
        }
    }
}
