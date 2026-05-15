package org.springframework.data.redis.laboratory.l4.l4_06.domain;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 06.04 ACK 策略
 *
 * 消费结果包装。listener 拿到一条消息处理完，必须明确表态：
 *  - SUCCESS → ACK；
 *  - DUPLICATE → 幂等命中，已处理过，直接 ACK；
 *  - RETRY_LATER → 不 ACK，等下次 PEL 恢复或 redeliver；
 *  - DEAD_LETTER → 走 DLQ 后再 ACK，让消息从主 stream 里"治愈"。
 *
 * 把"决定 ACK 还是不 ACK"这件事抽成一个枚举返回值，
 * 而不是让业务到处直接调 ack/不 ack —— 这是策略模式的具象化。
 *
 * 新手避坑：
 *  - 直接 boolean 返回 → 会有业务把"成功也返回 false"等怪逻辑；
 *  - 不区分 DUPLICATE 和 SUCCESS → 监控指标分不清"幂等命中"和"真正业务成功"。
 */
public enum ConsumeResult {

    SUCCESS,
    DUPLICATE,
    RETRY_LATER,
    DEAD_LETTER,
}
