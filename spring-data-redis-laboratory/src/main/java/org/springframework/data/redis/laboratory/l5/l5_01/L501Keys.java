package org.springframework.data.redis.laboratory.l5.l5_01;

/**
 * L5-01 章节实验统一使用的 Redis key 前缀，避免污染共享 Redis。
 * <p>
 * 全部 key 走 StringRedisSerializer，redis-cli 直接可读：
 * <pre>
 *   KEYS l5:01:*
 *   TYPE l5:01:user:profile:u-1001
 *   GET  l5:01:user:profile:u-1001
 *   HGETALL l5:01:cart:u-1001
 * </pre>
 * 实验结束后建议用 {@code redis-cli --scan --pattern 'l5:01:*' | xargs redis-cli del} 清理。
 */
public final class L501Keys {

    public static final String PREFIX = "l5:01:";

    /** C 端用户画像缓存：value 走 GenericJackson2JsonRedisSerializer。 */
    public static final String USER_PROFILE = PREFIX + "user:profile:";

    /** JDK 序列化兼容陷阱：value 走 JdkSerializationRedisSerializer。 */
    public static final String ORDER_SNAPSHOT_JDK = PREFIX + "order:snapshot:jdk:";

    /** 多态活动卡片缓存：value 走 GenericJackson2JsonRedisSerializer，关键看 @class 元信息。 */
    public static final String ACTIVITY_CARDS = PREFIX + "activity:cards:";

    /** 购物车 Hash：演示 hashKeySerializer 错配。 */
    public static final String CART = PREFIX + "cart:";

    /** 断点调试通道。 */
    public static final String DEBUG = PREFIX + "debug:";

    private L501Keys() {}
}
