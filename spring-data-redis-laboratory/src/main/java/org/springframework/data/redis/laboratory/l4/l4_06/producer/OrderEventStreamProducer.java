package org.springframework.data.redis.laboratory.l4.l4_06.producer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_06.L406Keys;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEvent;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEventPayload;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEventType;
import org.springframework.data.redis.laboratory.l4.l4_06.support.L406Json;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 05.01 生产消息：opsForStream().add()
 *
 * 真实场景：
 *  用户下单成功后，订单中心需要把"订单创建/优惠券核销/支付成功/取消/完成"等关键事件
 *  写到统一 stream:order:event。库存、优惠券、通知、风控、埋点 5 个 group 各自消费。
 *  生产侧只发一次，下游通过 Consumer Group 各取所需。
 *
 * 关键 Spring Data Redis API：
 *  - {@link StringRedisTemplate#opsForStream()}
 *  - {@link StreamOperations#add(org.springframework.data.redis.connection.stream.Record)}
 *  - {@code StreamOperations#trim(K, long, boolean)}
 *  - {@link MapRecord} / {@link StreamRecords}
 *
 * 建议断点：
 *  - {@link StringRedisTemplate#opsForStream()} —— 看 RedisTemplate 怎么把 StreamOperations 对象 lazy 创建并缓存；
 *  - DefaultStreamOperations#add —— 进 SDR 内部，看序列化、execute 模板、connection.xAdd 三步走；
 *  - RedisTemplate#execute —— 看回调模板怎么管理 connection 生命周期；
 *  - LettuceStreamCommands#xAdd —— 真正下到 Lettuce 的命令。
 *
 * 设计选择 —— MapRecord vs ObjectRecord：
 *  - MapRecord：消息字段是 Map&lt;String,String&gt;，redis-cli XRANGE 直接可读，schema 演进灵活；
 *  - ObjectRecord：框架用 ObjectHashMapper 自动把 POJO 拆字段，看似省心，
 *    但消费侧反序列化会反向读 Map → POJO，一旦字段变更兼容性极差，C 端高并发不推荐；
 *  - 本生产者主推 MapRecord：meta 字段拍平、payload 一个 JSON 字段塞满业务体。
 *
 * 新手避坑：
 *  - 把整个事件直接 toString 写一个 field：redis-cli 看不到结构、消费者解析依赖 toString 顺序；
 *  - 不带 trim 写：单 stream 永远膨胀，Redis 内存被订单事件挤爆；
 *  - 用 maxLen 不带 ~ 近似剪枝：硬剪枝 O(N) 阻塞主线程，C 端高并发反向打主库；
 *  - eventId 用 messageId 代替：messageId 由 Redis 生成，但 trim 后就丢了，
 *    业务幂等 key 必须由生产者侧生成、塞进 payload。
 */
@Component
public class OrderEventStreamProducer {

    private final StringRedisTemplate stringRedisTemplate;

    /** trim 上限：保留近 100 万条事件，C 端按 100w/天 估算，足够 24h 内回放排错。 */
    private static final long STREAM_MAX_LEN = 1_000_000L;

    @Autowired
    public OrderEventStreamProducer(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 真正写 stream 的入口。所有便利方法（publishCreated / publishPaid 等）最终汇聚到这里。
     * 业务建议透出这个方法即可，不要在 service 里到处 opsForStream。
     */
    public RecordId publish(OrderEvent event) {
        Map<String, String> body = toMapRecordBody(event);

        MapRecord<String, String, String> record = StreamRecords
                .mapBacked(body)
                .withStreamKey(L406Keys.STREAM_ORDER_EVENT);

        StreamOperations<String, Object, Object> ops = stringRedisTemplate.opsForStream();
        // ↑ 断点 1：看 ops 是 DefaultStreamOperations 实例，且与 RedisTemplate 共享 serializer。

        RecordId recordId = ops.add(record);
        // ↑ 断点 2：进入 DefaultStreamOperations#add → execute → LettuceStreamCommands#xAdd。

        // 近似 trim（带 ~），让 Redis 自由选择小批量裁剪，避免 O(N) 长阻塞。
        // 真实生产可以改成"每 N 次写触发一次 trim"，进一步降低 trim 开销。
        ops.trim(L406Keys.STREAM_ORDER_EVENT, STREAM_MAX_LEN, /* approximateTrimming = */ true);

        return recordId;
    }

    /** 便利方法：下单成功事件 */
    public RecordId publishCreated(String orderId, String userId, String shopId,
                                   String traceId, OrderEventPayload payload) {
        return publish(buildEvent(OrderEventType.ORDER_CREATED, orderId, userId, shopId, traceId, payload));
    }

    /** 便利方法：优惠券使用事件 */
    public RecordId publishCouponApplied(String orderId, String userId, String shopId,
                                         String traceId, OrderEventPayload payload) {
        return publish(buildEvent(OrderEventType.ORDER_COUPON_APPLIED, orderId, userId, shopId, traceId, payload));
    }

    /** 便利方法：支付成功事件 */
    public RecordId publishPaid(String orderId, String userId, String shopId,
                                String traceId, OrderEventPayload payload) {
        return publish(buildEvent(OrderEventType.ORDER_PAID, orderId, userId, shopId, traceId, payload));
    }

    /** 便利方法：订单取消事件 */
    public RecordId publishCancelled(String orderId, String userId, String shopId,
                                     String traceId, OrderEventPayload payload) {
        return publish(buildEvent(OrderEventType.ORDER_CANCELLED, orderId, userId, shopId, traceId, payload));
    }

    /**
     * 把 OrderEvent 拍平成 Map：
     *  - meta（eventId/eventType/orderId/userId/shopId/traceId/occurredAt/version/source/retryCount）单独成字段；
     *  - payload 序列化成单一 JSON 字符串字段；
     *  - 这样 redis-cli XRANGE 一眼看清主线，又不丢复杂业务体。
     */
    private static Map<String, String> toMapRecordBody(OrderEvent event) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("eventId", event.getEventId());
        body.put("eventType", event.getEventType().name());
        body.put("orderId", nullToEmpty(event.getOrderId()));
        body.put("userId", nullToEmpty(event.getUserId()));
        body.put("shopId", nullToEmpty(event.getShopId()));
        body.put("traceId", nullToEmpty(event.getTraceId()));
        body.put("occurredAt", event.getOccurredAt().toString());
        body.put("version", String.valueOf(event.getVersion()));
        body.put("source", nullToEmpty(event.getSource()));
        body.put("retryCount", String.valueOf(event.getRetryCount()));
        body.put("payload", event.getPayload() == null ? "{}" : L406Json.toJson(event.getPayload()));
        return body;
    }

    private static OrderEvent buildEvent(OrderEventType type,
                                         String orderId, String userId, String shopId,
                                         String traceId, OrderEventPayload payload) {
        OrderEvent event = new OrderEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(type);
        event.setOrderId(orderId);
        event.setUserId(userId);
        event.setShopId(shopId);
        event.setTraceId(traceId == null ? UUID.randomUUID().toString() : traceId);
        event.setOccurredAt(Instant.now());
        event.setVersion(1);
        event.setSource("order-service");
        event.setRetryCount(0);
        event.setPayload(payload);
        return event;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
