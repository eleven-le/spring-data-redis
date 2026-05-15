package org.springframework.data.redis.laboratory.l5.l5_01.compat;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.laboratory.l5.l5_01.L501Keys;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 真实场景：订单快照缓存因 JDK 序列化版本不兼容引发的线上事故。
 * <p>
 * 对应文档：L5-01 → §5 第 2 坑 / §3 JdkSerializationRedisSerializer。
 * <p>
 * 模拟剧情：
 * 1) 老版本服务用默认的 JdkSerializationRedisSerializer 把 {@link OrderSnapshotV1} 写进 Redis；
 * 2) 缓存 TTL = 7 天，覆盖了上线变更窗口；
 * 3) 新版服务把字段名/类型从 {@code amount:double} 改成 {@code amount:BigDecimal}，
 * 或者加了一个没有默认值的字段，
 * 上线后从缓存读回旧字节，抛 {@code InvalidClassException} 或字段缺失。
 * <p>
 * 关键观察点：
 * <ul>
 *   <li>用 jdkRedisTemplate 写完后，redis-cli 看到的 value 是
 *       {@code "\xAC\xED\x00\x05sr\x00..."}——不可读、不可跨语言。</li>
 *   <li>真正炸的不是 Redis，是上线发布。所以这种序列化器在 C 端大型项目里几乎一律改成 JSON 或 Protobuf。</li>
 * </ul>
 * 运行前：本地 Redis 可达；JDK 8+。
 * 运行后观察：
 * <pre>
 *   redis-cli --no-raw GET "$(redis-cli KEYS 'l5:01:order:snapshot:jdk:*' | head -1)"
 *   redis-cli STRLEN  "l5:01:order:snapshot:jdk:O-10086"
 * </pre>
 * 断点建议：
 * <ul>
 *   <li>{@link org.springframework.data.redis.serializer.JdkSerializationRedisSerializer#serialize}</li>
 *   <li>{@link org.springframework.data.redis.serializer.JdkSerializationRedisSerializer#deserialize}</li>
 * </ul>
 * 学到什么：
 * 1) JDK 序列化字节 = 类全限定名 + serialVersionUID + 字段二进制；任意一处对不上就炸；
 * 2) "默认配置"的代价：RedisTemplate 不显式配 serializer 时退化为 JDK 序列化，
 *    很多线上事故的根源是"没人配"，而不是"配错了"。
 */
public class L501_03_JdkSerializerCompatibilityTrap {

    /**
     * 注意这里类型是 {@code RedisTemplate<Object, Object>}——JDK 序列化器接收 Object，不强制 String。
     */
    private final RedisTemplate<Object, Object> jdkTemplate;

    public L501_03_JdkSerializerCompatibilityTrap(RedisTemplate<Object, Object> jdkTemplate) {
        this.jdkTemplate = jdkTemplate;
    }

    private static String key(String orderId) {
        return L501Keys.ORDER_SNAPSHOT_JDK + orderId;
    }

    /**
     * 模拟"旧版本服务"写入快照。
     */
    public void writeAsOldService(OrderSnapshotV1 snapshot) {
        jdkTemplate.opsForValue().set(key(snapshot.getOrderId()), snapshot);
    }

    /**
     * 模拟"新版本服务"读取——如果 OrderSnapshotV1 的字段发生过不兼容修改，这里就会炸。
     */
    public OrderSnapshotV1 readAsNewService(String orderId) {
        Object o = jdkTemplate.opsForValue().get(key(orderId));
        return (OrderSnapshotV1) o;
    }

    public void evict(String orderId) {
        jdkTemplate.delete(key(orderId));
    }

    /**
     * 订单快照（V1）。
     * <p>
     * serialVersionUID 显式声明，是为了把"兼容性失败"控制在字段层面而不是类签名层面。
     * 你可以做的实验：
     * <ol>
     *   <li>跑一次写入；</li>
     *   <li>把 {@code amount} 类型从 BigDecimal 改成 double，重新启动 readAsNewService，观察 InvalidClassException；</li>
     *   <li>再把 serialVersionUID 改成 2L，观察异常变成 "local class incompatible"。</li>
     * </ol>
     */
    public static class OrderSnapshotV1 implements Serializable {
        private static final long serialVersionUID = 1L;

        private String orderId;
        private String userId;
        private BigDecimal amount;
        private String channel;
        private LocalDateTime createdAt;

        public OrderSnapshotV1() {
        }

        public OrderSnapshotV1(String orderId, String userId, BigDecimal amount,
                               String channel, LocalDateTime createdAt) {
            this.orderId = orderId;
            this.userId = userId;
            this.amount = amount;
            this.channel = channel;
            this.createdAt = createdAt;
        }

        public static OrderSnapshotV1 sample() {
            return new OrderSnapshotV1("O-10086", "u-1001",
                    new BigDecimal("39.90"), "wechat-pay", LocalDateTime.now());
        }

        public String getOrderId() {
            return orderId;
        }

        public String getUserId() {
            return userId;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public String getChannel() {
            return channel;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }

        public void setChannel(String channel) {
            this.channel = channel;
        }

        public void setCreatedAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof OrderSnapshotV1)) return false;
            OrderSnapshotV1 that = (OrderSnapshotV1) o;
            return Objects.equals(orderId, that.orderId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orderId);
        }

        @Override
        public String toString() {
            return "OrderSnapshotV1{" + orderId + "," + userId + "," + amount +
                    "," + channel + "," + createdAt + "}";
        }
    }
}
