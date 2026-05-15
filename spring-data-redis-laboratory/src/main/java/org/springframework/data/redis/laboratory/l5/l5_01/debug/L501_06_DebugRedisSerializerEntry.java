package org.springframework.data.redis.laboratory.l5.l5_01.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.laboratory.l5.l5_01.L501Keys;
import org.springframework.data.redis.laboratory.l5.l5_01.activity.L501_04_GenericJacksonTypeMetadataScenario;
import org.springframework.data.redis.laboratory.l5.l5_01.cart.L501_05_HashSerializerMismatchScenario;
import org.springframework.data.redis.laboratory.l5.l5_01.compat.L501_03_JdkSerializerCompatibilityTrap;
import org.springframework.data.redis.laboratory.l5.l5_01.config.L501_01_RedisSerializerLabConfig;
import org.springframework.data.redis.laboratory.l5.l5_01.profile.L501_02_UserProfileCacheScenario;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 断点调试总入口。
 * <p>
 * 对应文档：L5-01 → §10 断点调试路线。
 * <p>
 * 推荐断点路径（每打一个，Step Into 一次，能完整走一遍 Java 对象 ↔ byte[] 链路）：
 * <ol>
 *   <li>{@code RedisTemplate#opsForValue()} —— 看 ValueOperations 子门面如何懒生成。</li>
 *   <li>{@code DefaultValueOperations#set(Object, Object)} —— 看子门面如何转调模板方法。</li>
 *   <li>{@code AbstractOperations#rawKey(Object)} —— 看 keySerializer 怎么被取出来用。</li>
 *   <li>{@code AbstractOperations#rawValue(Object)} —— 看 valueSerializer 怎么被取出来用。</li>
 *   <li>{@code RedisSerializer#serialize(Object)} —— 真正的策略点：在这里换实现就换字节。</li>
 *   <li>{@code RedisTemplate#execute(RedisCallback, boolean, boolean)} —— 看 Spring 怎么管 Connection 生命周期。</li>
 *   <li>{@code RedisConnection#set(byte[], byte[])} —— 看最终发到 Redis 的就是 byte[]。</li>
 * </ol>
 * 学习套路：第一遍只跑 §1 UserProfile（值的 set/get），把链路走完；
 * 第二遍跑 §2 JDK 兼容性，对比同一条链路上序列化器是怎么被替换的；
 * 第三遍跑 §3 Hash 错配，重点看 HashOperations.rawHashKey / rawHashValue。
 * <p>
 * 运行前：本地 Redis 可达。运行后用：
 * <pre>
 *   redis-cli --scan --pattern 'l5:01:*' | xargs -n 50 redis-cli del
 * </pre>
 * 清理实验数据。
 */
public class L501_06_DebugRedisSerializerEntry {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(L501_01_RedisSerializerLabConfig.class)) {

            @SuppressWarnings("unchecked")
            RedisTemplate<String, Object> json = ctx.getBean("jsonRedisTemplate", RedisTemplate.class);
            @SuppressWarnings("unchecked")
            RedisTemplate<Object, Object> jdk = ctx.getBean("jdkRedisTemplate", RedisTemplate.class);
            RedisConnectionFactory factory = ctx.getBean(RedisConnectionFactory.class);

            section("① 用户画像 — JSON 序列化主链路");
            L501_02_UserProfileCacheScenario profile = new L501_02_UserProfileCacheScenario(json);
            L501_02_UserProfileCacheScenario.UserProfile p = L501_02_UserProfileCacheScenario.UserProfile.sample("u-1001");
            // 断点 ①：在下面这行右键 → "Step Into" 就进了 DefaultValueOperations.set
            profile.cache(p);
            System.out.println("写入: " + p);
            // 断点 ②：在下面这行 Step Into 看 deserializeValue → @class 还原
            System.out.println("读回: " + profile.load("u-1001"));
            profile.evict("u-1001");

            section("② JDK 序列化兼容陷阱 — 看 value 字节是不可读二进制");
            L501_03_JdkSerializerCompatibilityTrap jdkTrap = new L501_03_JdkSerializerCompatibilityTrap(jdk);
            L501_03_JdkSerializerCompatibilityTrap.OrderSnapshotV1 snap =
                    L501_03_JdkSerializerCompatibilityTrap.OrderSnapshotV1.sample();
            jdkTrap.writeAsOldService(snap);
            // 断点 ③:JdkSerializationRedisSerializer#serialize / deserialize
            System.out.println("写入: " + snap);
            System.out.println("读回: " + jdkTrap.readAsNewService(snap.getOrderId()));
            System.out.println("→ 现在去 redis-cli 看 value，是 \\xAC\\xED 开头的 JDK 序列化字节流，不可读、不可跨语言。");
            jdkTrap.evict(snap.getOrderId());

            section("③ 多态 ActivityCard — @class 元信息的价值");
            L501_04_GenericJacksonTypeMetadataScenario activity =
                    new L501_04_GenericJacksonTypeMetadataScenario(json);
            activity.cacheHomeCards("home:2026",
                    L501_04_GenericJacksonTypeMetadataScenario.sampleCards());
            // 断点 ④:在 ops.get 这一行 Step Into，看 GenericJackson2JsonRedisSerializer 如何按 @class 还原成 CouponCard / BannerCard / ProductCard
            activity.loadHomeCards("home:2026").forEach(c ->
                    System.out.println("[" + c.cardType() + "] " + c));
            activity.evict("home:2026");

            section("④ 购物车 Hash — hashKey 错配演示");
            L501_05_HashSerializerMismatchScenario cart = new L501_05_HashSerializerMismatchScenario(factory);
            cart.putItem("u-1001", new L501_05_HashSerializerMismatchScenario.CartItemSnapshot(
                    "100086", "经典美式", new java.math.BigDecimal("12.00"), 2, true, System.currentTimeMillis()));
            System.out.println("正确策略读取: " + cart.getItem("u-1001", "100086"));
            System.out.println("错误策略读取（伪丢数据）: " + cart.getItemWithWrongSerializer("u-1001", "100086"));
            System.out.println("→ redis-cli HKEYS l5:01:cart:u-1001 看到的字段名是 \"sku:100086\"，但错误 Template 序列化出的字节不一样，所以查不到。");
            cart.evict("u-1001");

            section("✅ 全部链路跑通。建议下一步：在 RedisSerializer#serialize 设一个条件断点 (key 包含 \"l5:01\")，从 ① 重新跑一遍走源码。");
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("════════ " + title + " ════════");
    }
}
