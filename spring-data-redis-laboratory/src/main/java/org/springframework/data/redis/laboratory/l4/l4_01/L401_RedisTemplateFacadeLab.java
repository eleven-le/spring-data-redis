package org.springframework.data.redis.laboratory.l4.l4_01;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.laboratory.util.RedisConfigUtils;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.io.Serializable;
import java.util.Map;

/**
 * L4-01 —— RedisOperations 门面手工组装实验室
 * <p>
 * 【实验意图】
 *   L3 阶段我们拿到的是 RedisConnection，它只认 byte[]。
 *   现在撕开 Spring Boot 自动装配的黑盒，亲手拼出 RedisTemplate：
 *     1. 手动塞一个 LettuceConnectionFactory（L3-01 的产物）
 *     2. 手动注入 Key/Value/HashKey/HashValue 四把序列化器
 *     3. 显式调 afterPropertiesSet()
 *     4. 分别拿 ValueOperations / HashOperations 读写
 *   全程眼睁睁看着 Java 对象 → JSON 字节 → RESP 协议 → Redis 存储的翻译过程。
 * <p>
 * 【为什么业务代码不能直接用 RedisConnection？】
 *   RedisConnection.set(byte[] key, byte[] value) —— 签名就决定了
 *   上层每次调用都要自己做 "Object → byte[]" 的脏活：
 *     String → getBytes(UTF_8)
 *     POJO   → ObjectMapper.writeValueAsBytes()
 *     Long   → String.valueOf().getBytes()
 *   一个 20 人团队里一定会出现 3 种以上的序列化姿势，
 *   跨业务线读缓存就是一场开盒盲盒 —— 这就是 RedisOperations 门面存在的根本原因。
 * <p>
 * 【运行前提】
 *   redis.properties 已配置 host/port/password
 *   Java 17+
 * <p>
 * 【预期输出】
 *   ① opsForValue.set/get 往返一个 Order 对象
 *   ② opsForHash.put/entries 往返一张用户档案表
 *   ③ 打印出 Redis 里实际存储的 JSON 字节形态（证明序列化器确实在干活）
 */
public class L401_RedisTemplateFacadeLab {

    private static final String VALUE_KEY = "lab:l401:order:10086";
    private static final String HASH_KEY  = "lab:l401:user:profile:u888";

    public static void main(String[] args) throws Exception {

        // ═══════════════════════════════════════════════════════════
        // ① 造连接工厂（L3-01 已经讲透，这里复用）
        // ═══════════════════════════════════════════════════════════
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration();
        cfg.setHostName(RedisConfigUtils.getHost());
        cfg.setPort(RedisConfigUtils.getPort());
        cfg.setDatabase(RedisConfigUtils.getDatabase());
        String pwd = RedisConfigUtils.getPassword();
        if (pwd != null && !pwd.isEmpty()) {
            cfg.setPassword(RedisPassword.of(pwd));
        }

        LettuceConnectionFactory factory = new LettuceConnectionFactory(cfg);
        factory.afterPropertiesSet();   // 不调这一行，Lettuce client 不会被真正创建

        // ═══════════════════════════════════════════════════════════
        // ② 手工拼装 RedisTemplate ——（平时 Spring Boot 自动做的事情）
        //    Template 默认的 JDK 序列化器会把 key 搞成 "\xAC\xED\x00\x05..." 这种鬼玩意，
        //    线上 redis-cli 根本 KEYS 不出来，所以一定要覆盖。
        // ═══════════════════════════════════════════════════════════
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(buildSafeMapper());

        template.setKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashKeySerializer(keySerializer);        // hash field 也是人读的字符串
        template.setHashValueSerializer(valueSerializer);    // hash value 走 JSON

        template.afterPropertiesSet();                       // 初始化 ScriptExecutor / defaultSerializer

        // ═══════════════════════════════════════════════════════════
        // ③ opsForValue() —— 门面分发的第一站
        //    注意：每次 opsForValue() 返回的 DefaultValueOperations 都持有对 template 的引用，
        //    所有底层活还是由 template.execute(RedisCallback) 干，子 Operations 只是语义封装。
        // ═══════════════════════════════════════════════════════════
        ValueOperations<String, Object> valueOps = template.opsForValue();

        Order order = new Order("O-10086", 39.9, "大杯少冰三分糖");
        valueOps.set(VALUE_KEY, order);
        Object back = valueOps.get(VALUE_KEY);

        System.out.println("═══════ ValueOperations 往返 ═══════");
        System.out.println("写入: " + order);
        System.out.println("读回: " + back + "   (class = " + back.getClass().getName() + ")");

        // 低层窥探：用 RedisCallback 拿到 Redis 里真正存的字节
        byte[] rawBytes = template.execute((org.springframework.data.redis.core.RedisCallback<byte[]>)
                connection -> connection.get(keySerializer.serialize(VALUE_KEY)));
        System.out.println("Redis 里实际存的: " + new String(rawBytes));
        // 你会看到：{"@class":"...Order","id":"O-10086","price":39.9,"note":"大杯少冰三分糖"}

        // ═══════════════════════════════════════════════════════════
        // ④ opsForHash() —— 同一个 Template 路由到另一个子门面
        // ═══════════════════════════════════════════════════════════
        HashOperations<String, String, Object> hashOps = template.opsForHash();
        hashOps.put(HASH_KEY, "nickname", "奶茶狂魔");
        hashOps.put(HASH_KEY, "level", 7);
        hashOps.put(HASH_KEY, "vipExpireAt", 1800000000000L);

        Map<String, Object> profile = hashOps.entries(HASH_KEY);
        System.out.println("\n═══════ HashOperations 往返 ═══════");
        profile.forEach((k, v) ->
                System.out.println("field=" + k + ", value=" + v + ", class=" + v.getClass().getSimpleName()));

        // ═══════════════════════════════════════════════════════════
        // ⑤ 收尾
        // ═══════════════════════════════════════════════════════════
        factory.destroy();
        System.out.println("\n✅ Template 门面翻译链路跑通: Object → Serializer → byte[] → RESP → Redis");
    }

    /**
     * Jackson 放开 private 字段可见性，避免 POJO 必须写 getter/setter。
     * 生产里不要无脑 activateDefaultTyping —— 有反序列化 RCE 风险，
     * 这里实验场景单独控制可接受的类范围。
     */
    private static ObjectMapper buildSafeMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        return mapper;
    }

    /**
     * 故意做成 Serializable + 有无参构造，让 Jackson 能反序列化回来。
     */
    public static class Order implements Serializable {
        private String id;
        private double price;
        private String note;

        public Order() { }
        public Order(String id, double price, String note) {
            this.id = id;
            this.price = price;
            this.note = note;
        }
        public String getId() { return id; }
        public double getPrice() { return price; }
        public String getNote() { return note; }

        @Override
        public String toString() {
            return "Order{id=" + id + ", price=" + price + ", note='" + note + "'}";
        }
    }
}
