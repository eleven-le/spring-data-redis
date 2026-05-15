package org.springframework.data.redis.laboratory.l4.l4_06.consumer;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_06.L406Keys;
import org.springframework.data.redis.laboratory.l4.l4_06.dlq.DeadLetterStreamPublisher;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.ConsumeResult;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEvent;
import org.springframework.data.redis.laboratory.l4.l4_06.idempotent.StreamConsumeIdempotentService;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 02 真实业务场景 → 用户通知
 *
 * 真实场景：
 *  下单成功 / 支付成功 / 订单取消都需要通知用户（短信 / 推送 / 站内信）。
 *  通知属于"弱一致 + 弱幂等"业务：
 *   - 重复发一次短信用户大不了多收一条，业务可接受 → DUPLICATE 也算成功；
 *   - 通知通道短暂不可用 → RETRY_LATER；
 *   - 模板不存在 / channel 配置非法 → DLQ。
 */
public class NotifyUserStreamConsumer extends AbstractOrderEventStreamListener {

    public NotifyUserStreamConsumer(StringRedisTemplate redis,
                                    StreamConsumeIdempotentService idempotent,
                                    DeadLetterStreamPublisher dlq,
                                    String consumerName) {
        super(redis, idempotent, dlq, L406Keys.GROUP_NOTIFY, consumerName);
    }

    @Override
    protected ConsumeResult handleEvent(OrderEvent event, MapRecord<String, String, String> record) {
        switch (event.getEventType()) {
            case ORDER_CREATED -> log("通知用户：下单成功 userId={} orderId={}", event.getUserId(), event.getOrderId());
            case ORDER_PAID -> log("通知用户：支付成功 userId={} orderId={}", event.getUserId(), event.getOrderId());
            case ORDER_CANCELLED -> log("通知用户：订单取消 userId={}", event.getUserId());
            case ORDER_COMPLETED -> log("通知用户：订单完成 userId={}", event.getUserId());
            default -> {
                /* 其它类型通知不做事 */
            }
        }
        return ConsumeResult.SUCCESS;
    }
}
