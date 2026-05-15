package org.springframework.data.redis.laboratory.l4.l4_06.domain;

import java.time.Instant;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 06.06 死信队列 DLQ
 *
 * 死信事件信封。不是把原 OrderEvent 简单丢进 DLQ —— 还要保留诊断元信息：
 *  - 原 stream key、原 messageId（去主 stream 里 XRANGE 还能定位）；
 *  - 原 group、consumer（确认是哪个能力的哪个实例处理失败）；
 *  - errorType / errorMessage（人工补偿时一眼分辨是参数问题还是依赖问题）；
 *  - failedAt、retryCount（分析重试次数与失败时间窗口）；
 *  - traceId（跨链路下钻）；
 *  - operatorHint（推荐补偿动作，例如 "请重发到风控 group"）。
 *
 * 新手避坑：
 *  - 把原始 messageId 丢了，主 stream 一旦做了 trim 就再也找不回原始字段；
 *  - DLQ 的 stream 同样需要 trim 上限，否则失败消息越积越多照样撑爆 Redis。
 */
public class StreamDeadLetterEvent {

    private String originStream;
    private String originMessageId;
    private String originGroup;
    private String originConsumer;

    private String eventId;
    private OrderEventType eventType;
    private String orderId;
    private String userId;
    private String traceId;
    private int retryCount;

    private BizErrorType errorType;
    private String errorMessage;
    private Instant failedAt;
    private String operatorHint;

    /** 原始 payload JSON。直接作为字符串保存，避免序列化二次失败。 */
    private String originPayloadJson;

    public StreamDeadLetterEvent() {
    }

    public String getOriginStream() {
        return originStream;
    }

    public void setOriginStream(String originStream) {
        this.originStream = originStream;
    }

    public String getOriginMessageId() {
        return originMessageId;
    }

    public void setOriginMessageId(String originMessageId) {
        this.originMessageId = originMessageId;
    }

    public String getOriginGroup() {
        return originGroup;
    }

    public void setOriginGroup(String originGroup) {
        this.originGroup = originGroup;
    }

    public String getOriginConsumer() {
        return originConsumer;
    }

    public void setOriginConsumer(String originConsumer) {
        this.originConsumer = originConsumer;
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

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public BizErrorType getErrorType() {
        return errorType;
    }

    public void setErrorType(BizErrorType errorType) {
        this.errorType = errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public void setFailedAt(Instant failedAt) {
        this.failedAt = failedAt;
    }

    public String getOperatorHint() {
        return operatorHint;
    }

    public void setOperatorHint(String operatorHint) {
        this.operatorHint = operatorHint;
    }

    public String getOriginPayloadJson() {
        return originPayloadJson;
    }

    public void setOriginPayloadJson(String originPayloadJson) {
        this.originPayloadJson = originPayloadJson;
    }
}
