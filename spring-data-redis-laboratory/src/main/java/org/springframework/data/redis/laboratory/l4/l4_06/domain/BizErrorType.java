package org.springframework.data.redis.laboratory.l4.l4_06.domain;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 06.04 ACK 策略
 *
 * 业务错误分型。决定消费失败之后的走向：
 *  - RETRYABLE：网络抖动、Redis 短暂抖动、库存中心瞬时不可用 → 不 ACK，等下次 redeliver；
 *  - NON_RETRYABLE：参数非法、版本不兼容、业务校验失败 → 写 DLQ + ACK，避免毒丸消息卡 group；
 *  - POISONOUS：重试次数超阈值依旧失败 → 强制走 DLQ；
 *  - IDEMPOTENT_DUPLICATE：幂等判定为已处理 → 直接 ACK，不进入 DLQ。
 *
 * 新手避坑：
 *  - 把所有异常都 catch 成 NON_RETRYABLE → 真实重试机会被吞掉；
 *  - 把所有异常都 RETRYABLE → 毒丸消息反复重试拖垮整个 group。
 */
public enum BizErrorType {

    RETRYABLE,
    NON_RETRYABLE,
    POISONOUS,
    IDEMPOTENT_DUPLICATE,
}
