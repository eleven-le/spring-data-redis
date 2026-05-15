package org.springframework.data.redis.laboratory.l4.l4_06.domain;

import java.time.Instant;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 06.03 消息体设计
 *
 * 标准事件信封。stream 里"meta"字段拍扁，"payload"作为单独 JSON 字段，便于：
 *  - 用 redis-cli XRANGE 直接看清 eventType / orderId / traceId；
 *  - 不同消费者可以只读 meta 决定是否需要继续反序列化大 payload；
 *  - 多版本兼容（version 字段独立）。
 *
 * 字段：
 *  - eventId：全局唯一，用作幂等去重的天然 key；
 *  - eventType：事件类型枚举；
 *  - orderId / userId / shopId：业务三件套；
 *  - traceId：来自下单接口，跨服务链路；
 *  - occurredAt：事件发生时刻（生产者侧），不要消费者重新生成；
 *  - version：消息体版本号，灰度兼容用；
 *  - retryCount：消费者重试次数（消费侧维护，进入 DLQ 时一并保留）；
 *  - source：事件来源（order-service / payment-service / refund-service…）；
 *  - payload：复杂业务字段。
 *
 * 新手避坑：
 *  - 不要把 occurredAt 放消费者侧生成，事件回放时间会失真；
 *  - 不要把 traceId 留空，链路一断 C 端排查就是大海捞针。
 */
public class OrderEvent {

    private String eventId;
    private OrderEventType eventType;
    private String orderId;
    private String userId;
    private String shopId;
    private String traceId;
    private Instant occurredAt;
    private int version;
    private int retryCount;
    private String source;
    private OrderEventPayload payload;

    public OrderEvent() {
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public OrderEventType getEventType() {
        return eventType;
    }

    public void setEventType(OrderEventType eventType) {
        this.eventType = eventType;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getShopId() {
        return shopId;
    }

    public void setShopId(String shopId) {
        this.shopId = shopId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public OrderEventPayload getPayload() {
        return payload;
    }

    public void setPayload(OrderEventPayload payload) {
        this.payload = payload;
    }

    @Override
    public String toString() {
        return "OrderEvent{" +
                "eventId='" + eventId + '\'' +
                ", eventType=" + eventType +
                ", orderId='" + orderId + '\'' +
                ", traceId='" + traceId + '\'' +
                ", retryCount=" + retryCount +
                '}';
    }
}
