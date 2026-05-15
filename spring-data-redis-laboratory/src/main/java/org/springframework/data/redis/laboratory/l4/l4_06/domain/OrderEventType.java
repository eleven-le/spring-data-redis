package org.springframework.data.redis.laboratory.l4.l4_06.domain;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 06.03 消息体设计
 *
 * 订单事件类型。一个订单生命周期会触发多个事件，
 * 不同 group 关心不同事件类型（库存关心 ORDER_CREATED，优惠券关心 ORDER_COUPON_APPLIED 等）。
 *
 * 为什么把事件类型放在事件体里而不是用多个 stream key：
 *  - 不同事件类型的"业务时序"必须严格保留 → 单一 stream + 多个 group 天然保留；
 *  - 拆 stream 看似清晰，但跨事件类型的"先付款后核销优惠券"时序就需要业务层手工对齐；
 *  - 在 C 端高并发下，单 stream 性能足够，没必要先拆。
 *
 * 新手避坑：
 *  - 不要让事件类型成为字符串拼写，避免不同消费者对枚举值理解不一致；
 *  - 新增枚举值时务必兼容老消费者：未知枚举走默认分支，不要直接抛异常。
 */
public enum OrderEventType {

    /** 下单成功，库存预占的触发事件 */
    ORDER_CREATED,

    /** 订单使用优惠券，需要核销 */
    ORDER_COUPON_APPLIED,

    /** 支付成功，触发积分发放、通知、风控等 */
    ORDER_PAID,

    /** 订单取消，触发库存回滚、优惠券退还 */
    ORDER_CANCELLED,

    /** 订单完成 */
    ORDER_COMPLETED,
}
