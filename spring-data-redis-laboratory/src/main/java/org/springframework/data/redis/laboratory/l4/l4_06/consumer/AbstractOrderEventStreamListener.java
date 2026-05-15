package org.springframework.data.redis.laboratory.l4.l4_06.consumer;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_06.L406Keys;
import org.springframework.data.redis.laboratory.l4.l4_06.dlq.DeadLetterStreamPublisher;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.BizErrorType;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.ConsumeResult;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEvent;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEventPayload;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEventType;
import org.springframework.data.redis.laboratory.l4.l4_06.idempotent.IdempotentResult;
import org.springframework.data.redis.laboratory.l4.l4_06.idempotent.StreamConsumeIdempotentService;
import org.springframework.data.redis.laboratory.l4.l4_06.support.L406Json;
import org.springframework.data.redis.stream.StreamListener;

import java.time.Instant;
import java.util.Map;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 06.04 ACK 策略 + 06.05 幂等设计 + 06.06 死信队列
 *
 * 抽象消费者骨架。所有 5 个真实消费者继承本类，只实现 {@link #handleEvent(OrderEvent, MapRecord)}。
 *
 * 模板流程（核心 6 步）：
 *  1. raw MapRecord → OrderEvent（解析失败直接 DLQ + ACK）；
 *  2. 幂等抢锁；
 *  3. ALREADY_DONE → 直接 ACK，结束；
 *  4. LOCKED_BY_OTHER → 不 ACK，等下次 redeliver；
 *  5. 业务回调（FIRST_TIME / PREVIOUSLY_FAILED 都走这里）；
 *  6. 根据回调返回的 ConsumeResult → ACK / 不 ACK / DLQ。
 *
 * 设计偷师：
 *  - 这是模板方法 + 策略的混合：模板把"幂等/ACK/DLQ/异常"全部封死，
 *    业务侧只剩纯粹的领域逻辑，跟 RedisTemplate#execute 思路一致。
 *
 * 关键 Spring Data Redis API：
 *  - {@link StreamListener#onMessage(org.springframework.data.redis.connection.stream.Record)}
 *  - {@link org.springframework.data.redis.core.StreamOperations#acknowledge(Object, String, String...)}
 *  - 间接：{@code StreamMessageListenerContainer} / {@code StreamPollTask}
 *
 * 建议断点：
 *  - {@link #onMessage} 入口，对照 StreamPollTask 的 dispatch；
 *  - {@code DefaultStreamOperations#acknowledge} → LettuceStreamCommands#xAck。
 *
 * 新手避坑：
 *  - 在业务还没成功时 ACK：消息真实丢失；
 *  - 业务异常吞掉不上报：监控、告警、人工补偿全部失明；
 *  - DLQ 后忘了 ACK：消息会同时挂在主 stream 的 PEL 里，治不好；
 *  - 用 Stream Listener 的反序列化失败默默吞掉：永远卡 Pending。
 */
public abstract class AbstractOrderEventStreamListener
        implements StreamListener<String, MapRecord<String, String, String>> {

    /** 同一条消息允许的最大重试次数；超过强制走 DLQ。 */
    private static final int MAX_RETRY_BEFORE_DLQ = 5;

    protected final StringRedisTemplate redis;
    protected final StreamConsumeIdempotentService idempotent;
    protected final DeadLetterStreamPublisher dlq;
    protected final String group;
    protected final String consumerName;

    protected AbstractOrderEventStreamListener(StringRedisTemplate redis,
                                               StreamConsumeIdempotentService idempotent,
                                               DeadLetterStreamPublisher dlq,
                                               String group,
                                               String consumerName) {
        this.redis = redis;
        this.idempotent = idempotent;
        this.dlq = dlq;
        this.group = group;
        this.consumerName = consumerName;
    }

    public final String getGroup() {
        return group;
    }

    public final String getConsumerName() {
        return consumerName;
    }

    @Override
    public final void onMessage(MapRecord<String, String, String> record) {
        String messageId = record.getId().getValue();
        Map<String, String> body = record.getValue();

        OrderEvent event;
        try {
            event = parse(body);
        } catch (RuntimeException parseEx) {
            // 解析失败属于不可重试错误：消息体结构损坏，留在 PEL 只会反复失败。
            dlq.publishRaw(body, messageId, group, consumerName,
                    "Parse failed: " + parseEx.getClass().getSimpleName() + ": " + parseEx.getMessage(),
                    "请人工核查消息格式版本兼容");
            ack(record);
            log("[parse-fail→DLQ→ACK] msgId={} ex={}", messageId, parseEx.getMessage());
            return;
        }

        // 重试次数超阈值：直接 DLQ，不再纠缠业务。
        if (event.getRetryCount() >= MAX_RETRY_BEFORE_DLQ) {
            dlq.publish(event, messageId, group, consumerName,
                    BizErrorType.POISONOUS, "retryCount=" + event.getRetryCount() + " reaches limit",
                    "消息已被重试超过阈值，请人工排查");
            ack(record);
            log("[poisonous→DLQ→ACK] msgId={} retry={}", messageId, event.getRetryCount());
            return;
        }

        IdempotentResult idem = idempotent.tryAcquire(group, event.getEventId());
        if (idem == IdempotentResult.ALREADY_DONE) {
            ack(record);
            log("[duplicate→ACK] msgId={} eventId={}", messageId, event.getEventId());
            return;
        }
        if (idem == IdempotentResult.LOCKED_BY_OTHER) {
            log("[locked-by-other→no-ack] msgId={} eventId={}", messageId, event.getEventId());
            return;
        }

        ConsumeResult result;
        try {
            result = handleEvent(event, record);
        } catch (Exception ex) {
            BizErrorType errorType = classify(ex);
            if (errorType == BizErrorType.NON_RETRYABLE) {
                dlq.publish(event, messageId, group, consumerName,
                        errorType, ex.getClass().getName() + ": " + ex.getMessage(),
                        "业务校验失败 / 不可重试，请人工修复后重发");
                idempotent.markFailed(group, event.getEventId());
                ack(record);
                log("[non-retryable→DLQ→ACK] msgId={} ex={}", messageId, ex.toString());
            } else {
                idempotent.markFailed(group, event.getEventId());
                log("[retryable→no-ack] msgId={} ex={}", messageId, ex.toString());
            }
            return;
        }

        switch (result) {
            case SUCCESS -> {
                idempotent.markCompleted(group, event.getEventId());
                ack(record);
                log("[success→ACK] msgId={} eventId={}", messageId, event.getEventId());
            }
            case DUPLICATE -> {
                idempotent.markCompleted(group, event.getEventId());
                ack(record);
                log("[business-duplicate→ACK] msgId={} eventId={}", messageId, event.getEventId());
            }
            case RETRY_LATER -> {
                idempotent.markFailed(group, event.getEventId());
                log("[retry-later→no-ack] msgId={} eventId={}", messageId, event.getEventId());
            }
            case DEAD_LETTER -> {
                dlq.publish(event, messageId, group, consumerName,
                        BizErrorType.NON_RETRYABLE, "Business returned DEAD_LETTER",
                        "业务方主动判定无法处理");
                idempotent.markFailed(group, event.getEventId());
                ack(record);
                log("[business-dead-letter→DLQ→ACK] msgId={} eventId={}", messageId, event.getEventId());
            }
        }
    }

    /** 真正的业务回调，子类实现。 */
    protected abstract ConsumeResult handleEvent(OrderEvent event,
                                                 MapRecord<String, String, String> record);

    /**
     * 默认异常分型：子类可覆盖。把"业务校验异常"标 NON_RETRYABLE，其他默认 RETRYABLE。
     * 真实生产建议引入 BusinessException 体系，按 errorCode 分型。
     */
    protected BizErrorType classify(Exception ex) {
        if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
            return BizErrorType.NON_RETRYABLE;
        }
        return BizErrorType.RETRYABLE;
    }

    protected final void ack(MapRecord<String, String, String> record) {
        redis.opsForStream().acknowledge(L406Keys.STREAM_ORDER_EVENT, group, record.getId());
    }

    /** Map → OrderEvent 反向解析。与 {@code OrderEventStreamProducer#toMapRecordBody} 对偶。 */
    public static OrderEvent parse(Map<String, String> body) {
        OrderEvent event = new OrderEvent();
        event.setEventId(body.get("eventId"));
        event.setEventType(OrderEventType.valueOf(body.get("eventType")));
        event.setOrderId(body.get("orderId"));
        event.setUserId(body.get("userId"));
        event.setShopId(body.get("shopId"));
        event.setTraceId(body.get("traceId"));
        event.setOccurredAt(Instant.parse(body.get("occurredAt")));
        event.setVersion(parseIntSafe(body.get("version"), 1));
        event.setRetryCount(parseIntSafe(body.get("retryCount"), 0));
        event.setSource(body.get("source"));
        String payloadJson = body.getOrDefault("payload", "{}");
        event.setPayload(L406Json.fromJson(payloadJson, OrderEventPayload.class));
        return event;
    }

    private static int parseIntSafe(String s, int dft) {
        if (s == null || s.isEmpty()) return dft;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return dft;
        }
    }

    protected final void log(String fmt, Object... args) {
        System.out.printf("[L406][%s][%s] %s%n",
                group, consumerName, render(fmt, args));
    }

    private static String render(String fmt, Object... args) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (int p = 0; p < fmt.length(); ) {
            int idx = fmt.indexOf("{}", p);
            if (idx < 0) {
                sb.append(fmt, p, fmt.length());
                break;
            }
            sb.append(fmt, p, idx);
            sb.append(i < args.length ? args[i++] : "{}");
            p = idx + 2;
        }
        return sb.toString();
    }
}
