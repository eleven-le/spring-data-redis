package org.springframework.data.redis.laboratory.l5.l5_02.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l5.l5_02.L502Keys;
import org.springframework.data.redis.laboratory.l5.l5_02.cart.L502_05_HashFamilyCompareScenario;
import org.springframework.data.redis.laboratory.l5.l5_02.compare.L502_02_StringVsJsonVsJdkCompareScenario.OrderSnapshot;
import org.springframework.data.redis.laboratory.l5.l5_02.compare.L502_02_StringVsJsonVsJdkCompareScenario.UserProfile;
import org.springframework.data.redis.laboratory.l5.l5_02.config.L502_01_SerializerFamilyLabConfig;

import java.math.BigDecimal;

/**
 * 断点调试总入口——把"五大序列化家族"的写入/读取/Hash 三条链路一次性串起来。
 * <p>
 * 对应文档：L5-02 → §10 断点调试路线。
 * <p>
 * 推荐断点路径（每打一个,Step Into 一次,能完整看到 Java 对象 ↔ byte[] 的策略切换）：
 * <ol>
 *   <li>{@code DefaultValueOperations.set(K, V)} —— 子门面转 raw + execute；</li>
 *   <li>{@code AbstractOperations.rawKey(Object)} —— keySerializer 在这取出；</li>
 *   <li>{@code AbstractOperations.rawValue(Object)} —— valueSerializer 在这取出；</li>
 *   <li>{@code RedisSerializer.serialize(Object)} —— <b>策略真正工作点</b>；
 *       同一个调用栈,String / JSON / JDK 走的是不同实现；</li>
 *   <li>{@code AbstractOperations.deserializeValue(byte[])} —— 反向工作点；</li>
 *   <li>{@code AbstractOperations.rawHashKey} / {@code rawHashValue} —— Hash 双序列化点；</li>
 *   <li>{@code LettuceConnection.set(byte[], byte[])} —— byte[] 命令真正出网卡。</li>
 * </ol>
 * <p>
 * 学习套路：
 * <ul>
 *   <li>第一遍只跑 ① Token,把链路走完,看 String 序列化在 byte[] 上的极简形态；</li>
 *   <li>第二遍跑 ② UserProfile,对比同一个 set 入口走到的是 GenericJackson2Json.serialize；</li>
 *   <li>第三遍跑 ③ OrderSnapshot,看 JDK 序列化的字节流头部 `0xAC 0xED`；</li>
 *   <li>第四遍跑 ④ Hash,重点看 rawHashKey/rawHashValue,这是 §10.3 的核心。</li>
 * </ul>
 * 运行前：本地 Redis 可达。运行后清理：
 * <pre>
 *   redis-cli --scan --pattern 'l5:02:*' | xargs -n 50 redis-cli del
 * </pre>
 */
public class L502_06_DebugSerializerFamilyEntry {

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(L502_01_SerializerFamilyLabConfig.class)) {

            StringRedisTemplate stringTemplate = ctx.getBean(StringRedisTemplate.class);
            RedisTemplate<String, Object> jsonTemplate =
                    (RedisTemplate<String, Object>) ctx.getBean("genericJsonRedisTemplate", RedisTemplate.class);
            RedisTemplate<Object, Object> jdkTemplate =
                    (RedisTemplate<Object, Object>) ctx.getBean("jdkRedisTemplate", RedisTemplate.class);
            RedisConnectionFactory factory = ctx.getBean(RedisConnectionFactory.class);

            section("① Token — StringRedisSerializer 主链路");
            // 断点 ①:Step Into → DefaultValueOperations.set
            stringTemplate.opsForValue().set(L502Keys.TOKEN_USER + "u-1001", "tk-ABCDEF");
            // 断点 ②:Step Into → AbstractOperations.deserializeValue
            System.out.println("Token 读回 = " + stringTemplate.opsForValue().get(L502Keys.TOKEN_USER + "u-1001"));
            stringTemplate.delete(L502Keys.TOKEN_USER + "u-1001");

            section("② UserProfile — GenericJackson2Json 主链路");
            UserProfile profile = UserProfile.sample("u-1001");
            // 断点 ③:在 set 这行 Step Into,一路走到 GenericJackson2JsonRedisSerializer.serialize
            //         看 ObjectMapper 输出的 JSON 第一个字段是不是 "@class"
            jsonTemplate.opsForValue().set(L502Keys.PROFILE_USER + "u-1001", profile);
            // 断点 ④:get 这行 Step Into,看反序列化时 Jackson 如何按 @class 找 Class.forName
            System.out.println("Profile 读回 = " + jsonTemplate.opsForValue().get(L502Keys.PROFILE_USER + "u-1001"));
            jsonTemplate.delete(L502Keys.PROFILE_USER + "u-1001");

            section("③ OrderSnapshot — JDK 序列化字节流");
            OrderSnapshot snap = OrderSnapshot.sample("O-10086", "u-1001");
            // 断点 ⑤:Step Into → JdkSerializationRedisSerializer.serialize
            //         返回值上把鼠标停留,前 2 字节是 -84,-19 即 0xAC,0xED
            jdkTemplate.opsForValue().set(L502Keys.ORDER_SNAPSHOT_JDK + "O-10086", snap);
            System.out.println("Order 读回 = " + jdkTemplate.opsForValue().get(L502Keys.ORDER_SNAPSHOT_JDK + "O-10086"));
            jdkTemplate.delete(L502Keys.ORDER_SNAPSHOT_JDK + "O-10086");

            section("④ 购物车 Hash — rawHashKey 错配演示");
            L502_05_HashFamilyCompareScenario cart = new L502_05_HashFamilyCompareScenario(factory);
            L502_05_HashFamilyCompareScenario.CartItemSnapshot item =
                    new L502_05_HashFamilyCompareScenario.CartItemSnapshot(
                            "100086", "经典美式", new BigDecimal("12.00"), 2, true, System.currentTimeMillis());
            // 断点 ⑥:Step Into → AbstractOperations.rawHashKey
            //         看正确策略产出的 byte[](10 字节 ASCII)和错误策略的 byte[](20+ 字节 JDK 二进制)差异
            cart.putItemCorrect("u-1001", item);
            System.out.println("正确策略读取   = " + cart.getItemCorrect("u-1001", "100086"));
            System.out.println("错误 hashKey 读 = " + cart.getItemWithWrongSerializer("u-1001", "100086"));
            cart.evict("u-1001");

            section("✅ 五条链路跑通。下一步：");
            System.out.println("   1) 在 RedisSerializer#serialize 设条件断点(key contains \"l5:02\"),从 ① 重跑;");
            System.out.println("   2) 把第一个断点放在 AbstractOperations#rawValue,体会同一调用点不同实现的策略模式;");
            System.out.println("   3) 在 LettuceConnection 的 stringCommands().set/get 也加一行,看 byte[] 真正出网卡。");
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("══════ " + title + " ══════");
    }
}
