package org.springframework.data.redis.laboratory.l5.l5_02.cart;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.laboratory.l5.l5_02.L502Keys;
import org.springframework.data.redis.laboratory.l5.l5_02.config.L502_01_SerializerFamilyLabConfig;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 真实场景：购物车 Hash 缓存。
 * <p>
 * Redis 结构：
 * <pre>
 *   cart:{userId}
 *     sku:{skuId} -> CartItemSnapshot
 * </pre>
 * 对应文档：L5-02 → §3.4 Hash 数据 / §6 StringRedisTemplate 与 RedisTemplate 混用问题。
 * <p>
 * 这一节构造两套"看起来一样、其实序列化策略不同"的 RedisTemplate：
 * <ul>
 *   <li>{@link #correctTemplate}：hashKey 走 StringRedisSerializer,hashValue 走 JSON。</li>
 *   <li>{@link #wrongTemplate}：hashKey 不小心用了 JdkSerializationRedisSerializer。</li>
 * </ul>
 * 然后演示：用正确策略写入 → 用错误策略读取,HGET 一律 miss。
 * <p>
 * 这就是 C 端线上**最容易把"数据丢了"定性为真事故的伪事故场景**：
 * 数据其实在 Redis 里,只是 hashKey 序列化出的字节不同,HGET 命中不到字段。
 * <p>
 * 运行后 redis-cli 观察：
 * <pre>
 *   HKEYS l5:02:cart:correct:u-1001
 *   # 正确: "sku:100086"、"sku:200001"（人类可读）
 *   HKEYS l5:02:cart:wrong:u-1001
 *   # 错误: "\xAC\xED\x00\x05t\x00\nsku:100086"（JDK 二进制乱码）
 * </pre>
 */
public class L502_05_HashFamilyCompareScenario {

    private final RedisTemplate<String, Object> correctTemplate;
    private final RedisTemplate<String, Object> wrongTemplate;

    public L502_05_HashFamilyCompareScenario(RedisConnectionFactory factory) {
        this.correctTemplate = buildTemplate(factory,
                new StringRedisSerializer(),
                new GenericJackson2JsonRedisSerializer());
        // 故意让 hashKey 走 JDK 序列化——这是真实事故现场最常见的错配
        this.wrongTemplate = buildTemplate(factory,
                new JdkSerializationRedisSerializer(),
                new GenericJackson2JsonRedisSerializer());
    }

    private static RedisTemplate<String, Object> buildTemplate(RedisConnectionFactory factory,
                                                               RedisSerializer<?> hashKeySerializer,
                                                               RedisSerializer<?> hashValueSerializer) {
        RedisTemplate<String, Object> t = new RedisTemplate<>();
        t.setConnectionFactory(factory);
        StringRedisSerializer string = new StringRedisSerializer();
        t.setKeySerializer(string);
        t.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        t.setHashKeySerializer(hashKeySerializer);
        t.setHashValueSerializer(hashValueSerializer);
        t.afterPropertiesSet();
        return t;
    }

    private String correctKey(String userId) { return L502Keys.CART_CORRECT + userId; }
    private String wrongKey(String userId)   { return L502Keys.CART_WRONG + userId; }
    private static String fieldKey(String skuId) { return "sku:" + skuId; }

    /** 正确策略写入。 */
    public void putItemCorrect(String userId, CartItemSnapshot item) {
        HashOperations<String, Object, Object> ops = correctTemplate.opsForHash();
        ops.put(correctKey(userId), fieldKey(item.getSkuId()), item);
    }

    /** 正确策略读取。 */
    public CartItemSnapshot getItemCorrect(String userId, String skuId) {
        HashOperations<String, Object, Object> ops = correctTemplate.opsForHash();
        return (CartItemSnapshot) ops.get(correctKey(userId), fieldKey(skuId));
    }

    /** 错误策略读取——演示"HGET 查不到"伪事故。 */
    public Object getItemWithWrongSerializer(String userId, String skuId) {
        HashOperations<String, Object, Object> ops = wrongTemplate.opsForHash();
        // 注意:同一个 key,但读取使用的 hashKeySerializer 不一致 → HGET 字节不匹配
        return ops.get(correctKey(userId), fieldKey(skuId));
    }

    /** 错误策略写入,以便对比 HKEYS 字节形态。 */
    public void putItemWrong(String userId, CartItemSnapshot item) {
        HashOperations<String, Object, Object> ops = wrongTemplate.opsForHash();
        ops.put(wrongKey(userId), fieldKey(item.getSkuId()), item);
    }

    /** 用正确策略查完整购物车。 */
    public Map<Object, Object> dumpCorrect(String userId) {
        return correctTemplate.opsForHash().entries(correctKey(userId));
    }

    public void evict(String userId) {
        correctTemplate.delete(correctKey(userId));
        correctTemplate.delete(wrongKey(userId));
    }

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(L502_01_SerializerFamilyLabConfig.class)) {

            RedisConnectionFactory factory = ctx.getBean(RedisConnectionFactory.class);
            L502_05_HashFamilyCompareScenario lab = new L502_05_HashFamilyCompareScenario(factory);

            CartItemSnapshot item = new CartItemSnapshot("100086", "经典美式",
                    new BigDecimal("12.00"), 2, true, System.currentTimeMillis());

            // ① 正确写入
            lab.putItemCorrect("u-1001", item);
            System.out.println("[correct]   put OK,HKEYS 应为可读 'sku:100086'");
            System.out.println("[correct]   read = " + lab.getItemCorrect("u-1001", "100086"));

            // ② 错误读取(同一个 key,但 hashKey 序列化不一致)
            Object miss = lab.getItemWithWrongSerializer("u-1001", "100086");
            System.out.println("[wrong]     read same field with JDK hashKey = " + miss
                    + "  ← 伪丢数据现场");

            // ③ 错误写入(让你 redis-cli HKEYS 对比裸字节)
            lab.putItemWrong("u-1001", item);
            System.out.println();
            System.out.println("→ 现在去 redis-cli 对比：");
            System.out.println("  HKEYS l5:02:cart:correct:u-1001   # 'sku:100086' 可读");
            System.out.println("  HKEYS l5:02:cart:wrong:u-1001     # '\\xAC\\xED...' JDK 二进制");
            System.out.println("  ——同一个 \"sku:100086\" 字符串,序列化策略不同就是不同的 byte[],HGET 完全 miss。");

            // 清理
            lab.evict("u-1001");
        }
    }

    /** 购物车 Item——贴近 C 端真实业务字段。 */
    public static class CartItemSnapshot implements java.io.Serializable {
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

        @Override public String toString() {
            return "CartItemSnapshot{" + skuId + "," + skuName + ",×" + quantity +
                    ",单价=" + unitPrice + ",selected=" + selected + "}";
        }
    }
}
