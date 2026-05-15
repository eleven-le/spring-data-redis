package org.springframework.data.redis.laboratory.l4.l4_06.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 06.03 消息体设计
 *
 * 全模块共用的 JSON 编解码器：
 *  - 显式注册 JavaTimeModule，OrderEvent.occurredAt = Instant 直接可用；
 *  - 反序列化忽略未知字段，兼容上下游事件 schema 演进；
 *  - 序列化关闭"日期变 timestamp"，输出 ISO-8601 字符串，redis-cli 看得懂。
 *
 * 为什么不直接用 GenericJackson2JsonRedisSerializer：
 *  - Stream MapRecord 里 payload 是个普通 String 字段，不是 byte[]，需要直接拿 String 的 JSON 工具；
 *  - DLQ 写 stream 时也需要把整段事件序列化成字符串塞进 hash field。
 *
 * 新手避坑：
 *  - Jackson 默认不识别 java.time.Instant，不注册 JavaTimeModule 直接 NPE / 抛异常；
 *  - FAIL_ON_UNKNOWN_PROPERTIES 默认 true，上游加新字段就直接反序列化失败；
 *  - 不要每次 new ObjectMapper —— 它是线程安全且重对象，全局复用一份。
 */
public final class L406Json {

    private static final ObjectMapper MAPPER;

    static {
        MAPPER = new ObjectMapper();
        MAPPER.registerModule(new JavaTimeModule());
        MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MAPPER.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    private L406Json() {
    }

    public static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("L406 JSON serialize failed: " + o, e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("L406 JSON deserialize failed: " + json, e);
        }
    }
}
