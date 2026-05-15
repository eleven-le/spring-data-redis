package org.springframework.data.redis.laboratory.l4.l4_12.common;

/**
 * 章节内统一的 Redis Pub/Sub 频道命名常量。
 *
 * <p>生产经验：频道名一定要"主体.动作"两段式，并加业务前缀，避免不同业务线撞车。
 * 错误示例：channel="refresh"、"update"
 * 正确示例：channel="lab.product.changed"
 *
 * <p>不在这里写 Topic 实例（ChannelTopic / PatternTopic），因为同一个频道
 * 在不同 demo 里可能被精准订阅、也可能被 pattern 订阅，用法语义不同。
 */
public final class Topics {

    public static final String DEMO01_HELLO = "lab.l412.demo01.hello";

    public static final String PRODUCT_CHANGED = "lab.l412.product.changed";

    public static final String USER_STATUS_CHANGED = "lab.l412.user.status.changed";
    public static final String CONFIG_PATTERN = "lab.l412.config.*";
    public static final String CONFIG_CITY = "lab.l412.config.city";
    public static final String CONFIG_RISK = "lab.l412.config.risk";
    public static final String CONFIG_DELIVERY = "lab.l412.config.delivery";

    public static final String DEMO04_SLOW = "lab.l412.demo04.slow";
    public static final String DEMO04_BOOM = "lab.l412.demo04.boom";

    public static final String DEMO05_CONFIG_CHANGED = "lab.l412.demo05.config.changed";

    private Topics() {
    }
}
