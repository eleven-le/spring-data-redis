package org.springframework.data.redis.laboratory.l4.l4_06.idempotent;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 06.05 幂等设计
 *
 * 幂等枚举。把"幂等"这件事拆成 4 种状态：
 *  - FIRST_TIME：首次抢到锁，业务可以放心向下走；
 *  - LOCKED_BY_OTHER：另外一个并发消费者正在处理，本次直接忽略，不 ACK；
 *  - ALREADY_DONE：上一次已经成功处理（标记 completed），重复消息直接 ACK 走人；
 *  - PREVIOUSLY_FAILED：上次处理过但失败了，本次允许重试。
 *
 * 单单返回 boolean 不够：消费者拿到 false 之后不知道"是别人在处理"还是"已经成功过"，
 * ACK 决策完全不同。
 */
public enum IdempotentResult {

    FIRST_TIME,
    LOCKED_BY_OTHER,
    ALREADY_DONE,
    PREVIOUSLY_FAILED,
}
