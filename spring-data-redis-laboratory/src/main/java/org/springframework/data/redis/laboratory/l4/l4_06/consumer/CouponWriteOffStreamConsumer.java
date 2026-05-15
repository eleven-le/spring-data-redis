package org.springframework.data.redis.laboratory.l4.l4_06.consumer;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_06.L406Keys;
import org.springframework.data.redis.laboratory.l4.l4_06.dlq.DeadLetterStreamPublisher;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.ConsumeResult;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEvent;
import org.springframework.data.redis.laboratory.l4.l4_06.idempotent.StreamConsumeIdempotentService;

import java.util.List;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 02 + 06.05 幂等设计
 *
 * 真实场景：
 *  优惠券核销绝对不能重复执行 —— 一张优惠券被核销两次，财务核账直接对不上。
 *  幂等是核心，consumer group + (eventId) 双层兜底。
 *
 * 关注 ORDER_COUPON_APPLIED；其他事件忽略。
 */
public class CouponWriteOffStreamConsumer extends AbstractOrderEventStreamListener {

    public CouponWriteOffStreamConsumer(StringRedisTemplate redis,
                                        StreamConsumeIdempotentService idempotent,
                                        DeadLetterStreamPublisher dlq,
                                        String consumerName) {
        super(redis, idempotent, dlq, L406Keys.GROUP_COUPON, consumerName);
    }

    @Override
    protected ConsumeResult handleEvent(OrderEvent event, MapRecord<String, String, String> record) {
        if (event.getEventType() != org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEventType.ORDER_COUPON_APPLIED) {
            return ConsumeResult.SUCCESS;
        }
        List<String> couponIds = event.getPayload() == null ? null : event.getPayload().getCouponIds();
        if (couponIds == null || couponIds.isEmpty()) {
            throw new IllegalArgumentException("ORDER_COUPON_APPLIED 缺少 couponIds, orderId=" + event.getOrderId());
        }
        for (String couponId : couponIds) {
            // 真实业务调"优惠券中心 RPC 核销"；这里仅打印。
            log("优惠券核销 couponId={} orderId={} userId={} traceId={}",
                    couponId, event.getOrderId(), event.getUserId(), event.getTraceId());
        }
        return ConsumeResult.SUCCESS;
    }
}
