package org.springframework.data.redis.laboratory.l4.l4_06.consumer;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_06.dlq.DeadLetterStreamPublisher;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.ConsumeResult;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEvent;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEventPayload;
import org.springframework.data.redis.laboratory.l4.l4_06.idempotent.StreamConsumeIdempotentService;

import java.util.List;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 02 真实业务场景：C 端高并发订单事件流 → 库存预占
 *
 * 真实场景：
 *  下单成功后异步预占库存。库存中心是"强一致 + 可重试"的下游：
 *   - 网络抖动 / 库存中心抖动 → 不 ACK，等下次重投；
 *   - 商品下架 / sku 不存在 → 业务校验失败，进 DLQ；
 *   - 已经预占过（idempotent 命中） → 直接 ACK。
 *
 * 只关心 ORDER_CREATED 与 ORDER_CANCELLED；其他事件直接 SUCCESS（轻量过滤）。
 *
 * 建议断点：
 *  - {@link AbstractOrderEventStreamListener#onMessage} —— 看模板每一步状态切换；
 *  - {@link #handleEvent} —— 业务回调；
 *  - DefaultStreamOperations#acknowledge —— 看 ACK 真实下发。
 */
public class InventoryReserveStreamConsumer extends AbstractOrderEventStreamListener {

    public InventoryReserveStreamConsumer(StringRedisTemplate redis,
                                          StreamConsumeIdempotentService idempotent,
                                          DeadLetterStreamPublisher dlq,
                                          String consumerName) {
        super(redis, idempotent, dlq,
                org.springframework.data.redis.laboratory.l4.l4_06.L406Keys.GROUP_INVENTORY,
                consumerName);
    }

    @Override
    protected ConsumeResult handleEvent(OrderEvent event, MapRecord<String, String, String> record) {
        switch (event.getEventType()) {
            case ORDER_CREATED -> {
                List<OrderEventPayload.SkuLine> skuLines =
                        event.getPayload() == null ? null : event.getPayload().getSkuLines();
                if (skuLines == null || skuLines.isEmpty()) {
                    // 业务校验失败：参数非法 → 不可重试。
                    throw new IllegalArgumentException("ORDER_CREATED 缺少 skuLines, orderId=" + event.getOrderId());
                }
                for (OrderEventPayload.SkuLine line : skuLines) {
                    if (line.getQuantity() <= 0) {
                        throw new IllegalArgumentException("非法 skuQuantity sku=" + line.getSkuId());
                    }
                    // 真实业务在这里调库存中心 RPC 做预占；本实验只打印。
                    log("库存预占 sku={} qty={} orderId={} traceId={}",
                            line.getSkuId(), line.getQuantity(), event.getOrderId(), event.getTraceId());
                }
                return ConsumeResult.SUCCESS;
            }
            case ORDER_CANCELLED -> {
                log("库存回滚 orderId={} traceId={}", event.getOrderId(), event.getTraceId());
                return ConsumeResult.SUCCESS;
            }
            default -> {
                // 其他事件类型库存不关心，本质上等同于"消费完成"，避免消息一直 Pending。
                return ConsumeResult.SUCCESS;
            }
        }
    }
}
