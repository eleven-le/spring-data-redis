package org.springframework.data.redis.laboratory.l4.l4_06;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 06.01 Stream Key 设计
 *
 * 统一的 key / group / consumer 命名规范。
 *
 * 命名约定（C 端高并发常见落法）：
 *  - stream:{biz}:event              主业务事件 stream
 *  - stream:{biz}:event:dlq          死信 stream
 *  - group:{ability}                 一种业务能力（库存/优惠券/通知/风控/埋点）一个 group
 *  - consumer:{ability}:{instanceId} 同 group 内不同实例横向扩容
 *  - idempotent:stream:{group}:{eventId}  消费幂等锁 key
 *
 * 为什么把 stream key、group、consumer 命名规则收口在一处：
 *  - 排查问题时只需要看一个文件就知道命名规则；
 *  - C 端多业务线共用 Redis 时，前缀冲突极容易踩坑；
 *  - 单元测试和压测可以快速换前缀做隔离。
 */
public final class L406Keys {

    private L406Keys() {
    }

    /** 订单事件主 Stream */
    public static final String STREAM_ORDER_EVENT = "stream:order:event";

    /** 死信 Stream */
    public static final String STREAM_ORDER_EVENT_DLQ = "stream:order:event:dlq";

    /** 五个核心 Consumer Group */
    public static final String GROUP_INVENTORY = "group:inventory";
    public static final String GROUP_COUPON = "group:coupon";
    public static final String GROUP_NOTIFY = "group:notify";
    public static final String GROUP_RISK = "group:risk";
    public static final String GROUP_ANALYTICS = "group:analytics";

    /** Consumer name 模板：consumer:{ability}:{instanceId} */
    public static String consumer(String ability, String instanceId) {
        return "consumer:" + ability + ":" + instanceId;
    }

    /** 幂等 key：idempotent:stream:{group}:{eventId} */
    public static String idempotentKey(String group, String eventId) {
        return "idempotent:stream:" + group + ":" + eventId;
    }
}
