package org.springframework.data.redis.laboratory.l5.l5_02.compare;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l5.l5_02.L502Keys;
import org.springframework.data.redis.laboratory.l5.l5_02.config.L502_01_SerializerFamilyLabConfig;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 真实场景：登录态 / 用户画像 / 订单快照，三种数据形态分别走三种序列化器。
 * <p>
 * 对应文档：L5-02 → §3.2 简单 Value / §3.3 复杂对象 Value / §5 JDK 慎用。
 * <p>
 * 这一节的目的不是"演示 API 怎么用"，而是让你**亲眼看到** redis-cli 里的字节差异、
 * 体积差异、可读性差异——这就是序列化器选型的"物证"。
 * <p>
 * 运行后请配合 redis-cli 观察：
 * <pre>
 *   redis-cli --no-raw GET 'l5:02:token:user:u-1001'           # ASCII   ← String
 *   redis-cli --no-raw GET 'l5:02:profile:user:u-1001'         # {       ← JSON
 *   redis-cli --no-raw GET 'l5:02:order:snapshot:jdk:O-10086'  # \xAC\xED← JDK
 *
 *   redis-cli STRLEN 'l5:02:token:user:u-1001'                 # 几十字节
 *   redis-cli STRLEN 'l5:02:profile:user:u-1001'               # ~250 字节
 *   redis-cli STRLEN 'l5:02:order:snapshot:jdk:O-10086'        # ~500+ 字节
 * </pre>
 * 学到什么：
 * <ol>
 *   <li>不是所有数据都适合 JSON——Token 这种简单值 String 最合适；</li>
 *   <li>不是所有对象都应该 JDK 序列化——JSON 在排障和兼容上完胜；</li>
 *   <li>体积差异不是"几个字节"，而是"3-5 倍"——内存账要算的。</li>
 * </ol>
 */
public class L502_02_StringVsJsonVsJdkCompareScenario {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(L502_01_SerializerFamilyLabConfig.class)) {

            StringRedisTemplate stringTemplate = ctx.getBean(StringRedisTemplate.class);

            @SuppressWarnings("unchecked")
            RedisTemplate<String, Object> jsonTemplate =
                    ctx.getBean("genericJsonRedisTemplate", RedisTemplate.class);
            @SuppressWarnings("unchecked")
            RedisTemplate<Object, Object> jdkTemplate =
                    ctx.getBean("jdkRedisTemplate", RedisTemplate.class);

            RedisConnectionFactory factory = ctx.getBean(RedisConnectionFactory.class);

            // ① Token —— String 序列化主战场
            String tokenKey = L502Keys.TOKEN_USER + "u-1001";
            String token = "tk-2026-AB12CD34EF56";
            stringTemplate.opsForValue().set(tokenKey, token, 30, TimeUnit.MINUTES);

            // ② UserProfile —— Generic JSON 主战场
            String profileKey = L502Keys.PROFILE_USER + "u-1001";
            UserProfile profile = UserProfile.sample("u-1001");
            jsonTemplate.opsForValue().set(profileKey, profile, 7, TimeUnit.DAYS);

            // ③ OrderSnapshot —— 故意走 JDK,演示"开箱即坑"
            String orderKey = L502Keys.ORDER_SNAPSHOT_JDK + "O-10086";
            OrderSnapshot snapshot = OrderSnapshot.sample("O-10086", "u-1001");
            jdkTemplate.opsForValue().set(orderKey, snapshot, 1, TimeUnit.DAYS);

            // 用裸 byte[] 直接拉回来,绕开自动反序列化,亲眼看字节差异
            System.out.println();
            System.out.println("══════ 字节形态对比（绕过 Template 直接看 byte[]） ══════");
            inspectRaw(factory, tokenKey,   "Token  (String)");
            inspectRaw(factory, profileKey, "Profile(GenericJson)");
            inspectRaw(factory, orderKey,   "Order  (JDK)");

            // 反序列化回原对象
            System.out.println();
            System.out.println("══════ 反序列化验证 ══════");
            System.out.println("Token   = " + stringTemplate.opsForValue().get(tokenKey));
            System.out.println("Profile = " + jsonTemplate.opsForValue().get(profileKey));
            System.out.println("Order   = " + jdkTemplate.opsForValue().get(orderKey));

            System.out.println();
            System.out.println("→ 下一步：redis-cli STRLEN 三个 key,体积差异 1:5:10 量级；");
            System.out.println("  把 OrderSnapshot 加个字段重跑读取,JDK 会因 serialVersionUID 抛异常——");
            System.out.println("  这就是 L5-02 §5 真实事故时间线的微缩复现。");
        }
    }

    /** 直接拿连接看裸字节。用 StringRedisSerializer 把 key 序列化成 byte[],然后 RAW GET。 */
    private static void inspectRaw(RedisConnectionFactory factory, String key, String tag) {
        StringRedisSerializer s = new StringRedisSerializer();
        try (RedisConnection conn = factory.getConnection()) {
            byte[] rawKey = s.serialize(key);
            assert rawKey != null;
            byte[] raw = conn.stringCommands().get(rawKey);
            if (raw == null) {
                System.out.printf("%-22s  <miss>%n", tag);
                return;
            }
            System.out.printf("%-22s  size=%4d  head=%s  preview=%s%n",
                    tag, raw.length, headHex(raw, 4), preview(raw));
        }
    }

    private static String headHex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, b.length); i++) {
            sb.append(String.format("\\x%02X", b[i] & 0xff));
        }
        return sb.toString();
    }

    private static String preview(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(60, b.length); i++) {
            byte v = b[i];
            if (v >= 0x20 && v < 0x7f) sb.append((char) v);
            else sb.append('.');
        }
        if (b.length > 60) sb.append("...");
        return sb.toString();
    }

    /** C 端用户画像。Generic JSON 的主用户。 */
    public static class UserProfile {
        private String userId;
        private String nickname;
        private Integer vipLevel;
        private List<String> tags;
        private long updatedAtMillis;

        public UserProfile() {}
        public UserProfile(String userId, String nickname, Integer vipLevel,
                           List<String> tags, long updatedAtMillis) {
            this.userId = userId; this.nickname = nickname;
            this.vipLevel = vipLevel; this.tags = tags;
            this.updatedAtMillis = updatedAtMillis;
        }

        public static UserProfile sample(String userId) {
            return new UserProfile(userId, "鹿鸣", 3,
                    Arrays.asList("新客", "高客单", "夜间活跃"), System.currentTimeMillis());
        }

        public String getUserId()        { return userId; }
        public String getNickname()      { return nickname; }
        public Integer getVipLevel()     { return vipLevel; }
        public List<String> getTags()    { return tags; }
        public long getUpdatedAtMillis() { return updatedAtMillis; }
        public void setUserId(String s)         { this.userId = s; }
        public void setNickname(String s)       { this.nickname = s; }
        public void setVipLevel(Integer v)      { this.vipLevel = v; }
        public void setTags(List<String> t)     { this.tags = t; }
        public void setUpdatedAtMillis(long t)  { this.updatedAtMillis = t; }

        @Override public String toString() {
            return "UserProfile{" + userId + "," + nickname + ",vip" + vipLevel + ",tags=" + tags + "}";
        }
    }

    /**
     * 订单快照。故意实现 Serializable,只为演示 JDK 序列化字节差异和兼容陷阱。
     * 真实业务里订单快照应该走 Typed JSON DTO。
     */
    public static class OrderSnapshot implements Serializable {
        private static final long serialVersionUID = 1L;

        private String orderId;
        private String userId;
        private BigDecimal totalAmount;
        private int itemCount;
        private long createdAtMillis;

        public OrderSnapshot() {}
        public OrderSnapshot(String orderId, String userId, BigDecimal totalAmount,
                             int itemCount, long createdAtMillis) {
            this.orderId = orderId; this.userId = userId;
            this.totalAmount = totalAmount; this.itemCount = itemCount;
            this.createdAtMillis = createdAtMillis;
        }

        public static OrderSnapshot sample(String orderId, String userId) {
            return new OrderSnapshot(orderId, userId, new BigDecimal("128.50"),
                    3, System.currentTimeMillis());
        }

        public String getOrderId()         { return orderId; }
        public String getUserId()          { return userId; }
        public BigDecimal getTotalAmount() { return totalAmount; }
        public int getItemCount()          { return itemCount; }
        public long getCreatedAtMillis()   { return createdAtMillis; }

        @Override public String toString() {
            return "OrderSnapshot{" + orderId + "," + userId + ",¥" + totalAmount +
                    ",×" + itemCount + "}";
        }
    }
}
