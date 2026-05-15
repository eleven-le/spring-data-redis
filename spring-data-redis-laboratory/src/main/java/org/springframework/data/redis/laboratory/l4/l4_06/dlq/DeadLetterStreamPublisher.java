package org.springframework.data.redis.laboratory.l4.l4_06.dlq;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_06.L406Keys;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.BizErrorType;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEvent;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEventType;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.StreamDeadLetterEvent;
import org.springframework.data.redis.laboratory.l4.l4_06.support.L406Json;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 06.06 死信队列 DLQ
 *
 * 真实场景：
 *  优惠券消费者收到一条事件，反序列化失败 / 业务校验非法 / 重试 N 次仍失败。
 *  这种"毒丸消息"必须从主 stream 移走，否则 Pending 会一直挂着，
 *  group lag 报警永远不消，新人哪敢 ACK？
 *
 * 设计：
 *  - 把出错的事件复刻一份写入 stream:order:event:dlq；
 *  - 保留原 streamKey / messageId / group / consumer / traceId / errorType / errorMessage / failedAt；
 *  - 主 stream 上原消息务必 ACK，让它从 PEL 中消失；
 *  - 后续由人工 / 离线消费者从 DLQ 读取并补偿。
 *
 * 关键 Spring Data Redis API：
 *  - {@link StreamOperations#add(org.springframework.data.redis.connection.stream.Record)}
 *  - {@link StreamOperations#trim}
 *
 * 建议断点：
 *  - {@link DeadLetterStreamPublisher#publish}：观察 DLQ MapRecord 的字段；
 *  - DefaultStreamOperations#add 同 L4-06 / 05.01 链路，可对照看相同模板路径。
 *
 * 新手避坑：
 *  - DLQ 也是 stream，不做 trim 同样会被撑爆；
 *  - DLQ 上不要再挂 group 进入循环消费，否则毒丸会 ping-pong；
 *  - 写 DLQ 失败时不要把异常吃掉 —— 至少打印错误日志 + 告警；
 *  - 写 DLQ 之前必须先记录原 messageId，否则主 stream trim 之后人工补偿无从下手。
 */
@Component
public class DeadLetterStreamPublisher {

    private static final long DLQ_MAX_LEN = 100_000L;

    private final StringRedisTemplate redis;

    @Autowired
    public DeadLetterStreamPublisher(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 入口 1：业务侧已经把 OrderEvent 反序列化好。 */
    public RecordId publish(OrderEvent event,
                            String originMessageId, String group, String consumer,
                            BizErrorType errorType, String errorMessage,
                            String operatorHint) {
        StreamDeadLetterEvent dlq = new StreamDeadLetterEvent();
        dlq.setOriginStream(L406Keys.STREAM_ORDER_EVENT);
        dlq.setOriginMessageId(originMessageId);
        dlq.setOriginGroup(group);
        dlq.setOriginConsumer(consumer);
        dlq.setEventId(event.getEventId());
        dlq.setEventType(event.getEventType());
        dlq.setOrderId(event.getOrderId());
        dlq.setUserId(event.getUserId());
        dlq.setTraceId(event.getTraceId());
        dlq.setRetryCount(event.getRetryCount());
        dlq.setErrorType(errorType);
        dlq.setErrorMessage(safeTrim(errorMessage, 1024));
        dlq.setFailedAt(Instant.now());
        dlq.setOperatorHint(operatorHint);
        dlq.setOriginPayloadJson(event.getPayload() == null ? "{}" : L406Json.toJson(event.getPayload()));
        return doPublish(dlq);
    }

    /** 入口 2：连 OrderEvent 都解析失败，只能透传原始 raw map。 */
    public RecordId publishRaw(Map<String, String> rawBody,
                               String originMessageId, String group, String consumer,
                               String errorMessage, String operatorHint) {
        StreamDeadLetterEvent dlq = new StreamDeadLetterEvent();
        dlq.setOriginStream(L406Keys.STREAM_ORDER_EVENT);
        dlq.setOriginMessageId(originMessageId);
        dlq.setOriginGroup(group);
        dlq.setOriginConsumer(consumer);
        dlq.setEventId(rawBody.getOrDefault("eventId", "unknown"));
        try {
            dlq.setEventType(OrderEventType.valueOf(rawBody.getOrDefault("eventType", "ORDER_CREATED")));
        } catch (IllegalArgumentException ignore) {
            dlq.setEventType(null);
        }
        dlq.setOrderId(rawBody.get("orderId"));
        dlq.setUserId(rawBody.get("userId"));
        dlq.setTraceId(rawBody.get("traceId"));
        dlq.setErrorType(BizErrorType.NON_RETRYABLE);
        dlq.setErrorMessage(safeTrim(errorMessage, 1024));
        dlq.setFailedAt(Instant.now());
        dlq.setOperatorHint(operatorHint);
        dlq.setOriginPayloadJson(rawBody.getOrDefault("payload", "{}"));
        return doPublish(dlq);
    }

    private RecordId doPublish(StreamDeadLetterEvent dlq) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("originStream", nullToEmpty(dlq.getOriginStream()));
        body.put("originMessageId", nullToEmpty(dlq.getOriginMessageId()));
        body.put("originGroup", nullToEmpty(dlq.getOriginGroup()));
        body.put("originConsumer", nullToEmpty(dlq.getOriginConsumer()));
        body.put("eventId", nullToEmpty(dlq.getEventId()));
        body.put("eventType", dlq.getEventType() == null ? "" : dlq.getEventType().name());
        body.put("orderId", nullToEmpty(dlq.getOrderId()));
        body.put("userId", nullToEmpty(dlq.getUserId()));
        body.put("traceId", nullToEmpty(dlq.getTraceId()));
        body.put("retryCount", String.valueOf(dlq.getRetryCount()));
        body.put("errorType", dlq.getErrorType() == null ? "" : dlq.getErrorType().name());
        body.put("errorMessage", nullToEmpty(dlq.getErrorMessage()));
        body.put("failedAt", dlq.getFailedAt().toString());
        body.put("operatorHint", nullToEmpty(dlq.getOperatorHint()));
        body.put("originPayloadJson", nullToEmpty(dlq.getOriginPayloadJson()));

        MapRecord<String, String, String> record = StreamRecords
                .mapBacked(body)
                .withStreamKey(L406Keys.STREAM_ORDER_EVENT_DLQ);

        StreamOperations<String, Object, Object> ops = redis.opsForStream();
        RecordId rid = ops.add(record);
        ops.trim(L406Keys.STREAM_ORDER_EVENT_DLQ, DLQ_MAX_LEN, true);
        return rid;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String safeTrim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
