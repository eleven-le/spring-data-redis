package org.springframework.data.redis.laboratory.l4.l4_06.consumer;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_06.L406Keys;
import org.springframework.data.redis.laboratory.l4.l4_06.dlq.DeadLetterStreamPublisher;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.ConsumeResult;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEvent;
import org.springframework.data.redis.laboratory.l4.l4_06.idempotent.StreamConsumeIdempotentService;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 02 真实业务场景 → 数据埋点
 *
 * 真实场景：
 *  所有事件都打埋点送到 OLAP（ClickHouse / Doris / BigQuery）。
 *  埋点对一致性容忍度最高：
 *   - 允许重复（OLAP 侧用 eventId 去重）；
 *   - 允许丢失少量（业务相关性低）；
 *   - 但要"尽量不丢"，所以仍然走 ACK 而非 receiveAutoAck。
 *
 * 这是消费者中最简单的 group：所有事件都吃，几乎不抛异常。
 */
public class AnalyticsStreamConsumer extends AbstractOrderEventStreamListener {

    public AnalyticsStreamConsumer(StringRedisTemplate redis,
                                   StreamConsumeIdempotentService idempotent,
                                   DeadLetterStreamPublisher dlq,
                                   String consumerName) {
        super(redis, idempotent, dlq, L406Keys.GROUP_ANALYTICS, consumerName);
    }

    @Override
    protected ConsumeResult handleEvent(OrderEvent event, MapRecord<String, String, String> record) {
        log("埋点：eventType={} orderId={} userId={} occurredAt={}",
                event.getEventType(), event.getOrderId(), event.getUserId(), event.getOccurredAt());
        return ConsumeResult.SUCCESS;
    }
}
