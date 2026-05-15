package org.springframework.data.redis.laboratory.l4.l4_12.common;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 极简 JSON 工具，用于 Pub/Sub 消息体（业务 key + version + traceId）的序列化。
 *
 * <p>设计取舍：
 * - Pub/Sub 消息体应"小、扁平、可演化"，不要塞领域对象。Jackson 配置成
 *   宽松模式（未知字段忽略 + 字段可见），这样发布端加字段、消费端旧版本不会立即崩。
 * - 不复用 RedisTemplate 的 GenericJackson2JsonRedisSerializer，因为那个会写入
 *   类型信息（@class），导致跨服务 / 跨版本反序列化耦合死，Pub/Sub 跨节点广播尤其忌讳。
 */
public final class JsonCodec {

    private static final ObjectMapper MAPPER;

    static {
        ObjectMapper m = new ObjectMapper();
        m.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        MAPPER = m;
    }

    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("toJson failed", e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("fromJson failed: " + json, e);
        }
    }

    private JsonCodec() {
    }
}
