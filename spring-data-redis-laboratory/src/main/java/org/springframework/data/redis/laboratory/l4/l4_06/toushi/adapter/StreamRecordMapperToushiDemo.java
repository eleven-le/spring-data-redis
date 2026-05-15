package org.springframework.data.redis.laboratory.l4.l4_06.toushi.adapter;

import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEvent;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEventPayload;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEventType;
import org.springframework.data.redis.laboratory.l4.l4_06.support.L406Json;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 偷师 → Adapter / Mapper
 *
 * 偷师对象：MapRecord ↔ 业务领域对象的转换层（Spring Data Redis 内部对应 ObjectHashMapper / Jackson2HashMapper）
 *
 * 它解决了什么问题：
 *  - Redis Stream 的 raw 消息是 Map&lt;String,String&gt;，业务域是 OrderEvent；
 *  - 让业务直接读 Map 极其脆弱：字段重命名、版本演进、字段缺失全靠"约定"；
 *  - 把 Map → Object 的转换收口在 Mapper：
 *      a) 解析失败有统一的兜底；
 *      b) 字段演进只改一处；
 *      c) 消费者签名一直是 OrderEvent，不暴露 Map。
 *
 * 在 C 端业务里如何采纳：
 *  - 任意"raw 数据 → 业务对象"的边界：HTTP 请求体、外部 webhook、Kafka 消息、Excel 行；
 *  - Mapper 集中处理空值、字段名变更、单位换算、enum 安全转换；
 *  - 业务方法只见 OrderEvent / Refund / RiskAlert 这种领域类。
 */
public class StreamRecordMapperToushiDemo {

    /** 抽象 Mapper 接口 —— 对应 SDR 的 HashMapper. */
    public interface RecordMapper<T> {
        Map<String, String> toMap(T value);

        T fromMap(Map<String, String> map);
    }

    /** OrderEvent 专用 Mapper：把 meta 拍平 + payload JSON。 */
    public static class OrderEventRecordMapper implements RecordMapper<OrderEvent> {

        @Override
        public Map<String, String> toMap(OrderEvent event) {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("eventId", nullToEmpty(event.getEventId()));
            body.put("eventType", event.getEventType() == null ? "" : event.getEventType().name());
            body.put("orderId", nullToEmpty(event.getOrderId()));
            body.put("userId", nullToEmpty(event.getUserId()));
            body.put("shopId", nullToEmpty(event.getShopId()));
            body.put("traceId", nullToEmpty(event.getTraceId()));
            body.put("occurredAt", event.getOccurredAt() == null ? "" : event.getOccurredAt().toString());
            body.put("version", String.valueOf(event.getVersion()));
            body.put("source", nullToEmpty(event.getSource()));
            body.put("retryCount", String.valueOf(event.getRetryCount()));
            body.put("payload", event.getPayload() == null ? "{}" : L406Json.toJson(event.getPayload()));
            return body;
        }

        @Override
        public OrderEvent fromMap(Map<String, String> body) {
            OrderEvent event = new OrderEvent();
            event.setEventId(body.get("eventId"));
            String typeStr = body.get("eventType");
            // 偷师点：未知 enum 不抛异常，让消费者自己决定怎么 fallback。
            event.setEventType(parseEnumSafely(typeStr));
            event.setOrderId(body.get("orderId"));
            event.setUserId(body.get("userId"));
            event.setShopId(body.get("shopId"));
            event.setTraceId(body.get("traceId"));
            event.setOccurredAt(parseInstantSafely(body.get("occurredAt")));
            event.setVersion(parseIntSafely(body.get("version"), 1));
            event.setRetryCount(parseIntSafely(body.get("retryCount"), 0));
            event.setSource(body.get("source"));
            String payloadJson = body.getOrDefault("payload", "{}");
            event.setPayload(L406Json.fromJson(payloadJson, OrderEventPayload.class));
            return event;
        }

        private static OrderEventType parseEnumSafely(String s) {
            if (s == null || s.isEmpty()) return null;
            try {
                return OrderEventType.valueOf(s);
            } catch (IllegalArgumentException ignore) {
                return null;
            }
        }

        private static Instant parseInstantSafely(String s) {
            if (s == null || s.isEmpty()) return null;
            try {
                return Instant.parse(s);
            } catch (Exception ex) {
                return null;
            }
        }

        private static int parseIntSafely(String s, int dft) {
            if (s == null || s.isEmpty()) return dft;
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ex) {
                return dft;
            }
        }

        private static String nullToEmpty(String s) {
            return s == null ? "" : s;
        }
    }

    public static void main(String[] args) {
        OrderEvent event = new OrderEvent();
        event.setEventId("evt-1");
        event.setEventType(OrderEventType.ORDER_CREATED);
        event.setOrderId("ORD-1");
        event.setUserId("U1");
        event.setTraceId("trace-1");
        event.setOccurredAt(Instant.now());
        event.setVersion(1);
        event.setRetryCount(0);
        event.setSource("order-service");

        OrderEventRecordMapper mapper = new OrderEventRecordMapper();
        Map<String, String> map = mapper.toMap(event);
        System.out.println("[toushi-mapper] toMap = " + map);

        OrderEvent back = mapper.fromMap(map);
        System.out.println("[toushi-mapper] fromMap = " + back);

        // 模拟"上游加了未知 eventType"，验证不抛异常。
        Map<String, String> futureMap = new LinkedHashMap<>(map);
        futureMap.put("eventType", "ORDER_FUTURE_NEW_TYPE");
        OrderEvent fallback = mapper.fromMap(futureMap);
        System.out.println("[toushi-mapper] unknown eventType → null, OrderEvent=" + fallback);
    }
}
