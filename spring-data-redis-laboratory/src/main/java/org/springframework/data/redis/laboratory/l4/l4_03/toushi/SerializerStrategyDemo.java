package org.springframework.data.redis.laboratory.l4.l4_03.toushi;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 偷师 3：Strategy（策略模式）—— RedisSerializer 的"骨头"。
 * <p>
 * RedisTemplate 自身只关心"何时序列化"，不关心"序列化成什么字节"。
 * 那是 keySerializer / valueSerializer / hashKeySerializer / hashValueSerializer 四把策略的事。
 * 只要换一把策略，同一个 Template 的存储字节就完全不同。
 * <p>
 * 把这套思想搬到业务：缓存模板内部不写死 JSON，对外暴露 Serializer 策略接口，
 * 谁想用 JSON 用 JSON，谁想用 Protobuf 用 Protobuf，谁想自定义大端二进制也行。
 */
public class SerializerStrategyDemo {

    interface Serializer<T> {
        byte[] serialize(T value);
        T deserialize(byte[] bytes, Class<T> type);
    }

    static class StringSerializer implements Serializer<String> {
        @Override public byte[] serialize(String value)              { return value.getBytes(StandardCharsets.UTF_8); }
        @Override public String deserialize(byte[] bytes, Class<String> t) { return new String(bytes, StandardCharsets.UTF_8); }
    }

    static class JsonSerializer<T> implements Serializer<T> {
        private final ObjectMapper mapper = new ObjectMapper()
                .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        @Override public byte[] serialize(T value) {
            try { return mapper.writeValueAsBytes(value); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        @Override public T deserialize(byte[] bytes, Class<T> type) {
            try { return mapper.readValue(bytes, type); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
    }

    /** 模拟一个超级简化的 RedisTemplate：只持有一个序列化策略 + 一个 K-V 内存仓。 */
    static class CacheTemplate<V> {
        private final Map<String, byte[]> store = new HashMap<>();
        private final Serializer<V> valueSerializer;

        CacheTemplate(Serializer<V> valueSerializer) { this.valueSerializer = valueSerializer; }

        public void put(String key, V value) { store.put(key, valueSerializer.serialize(value)); }

        public V get(String key, Class<V> type) {
            byte[] bytes = store.get(key);
            return bytes == null ? null : valueSerializer.deserialize(bytes, type);
        }

        public byte[] rawBytes(String key) { return store.get(key); }
    }

    static class Order {
        public String id;
        public double price;
        public Order() {}
        public Order(String id, double price) { this.id = id; this.price = price; }
        @Override public String toString() { return "Order{" + id + "," + price + "}"; }
    }

    public static void main(String[] args) {
        // 同样的模板，仅仅换一把策略，存的字节就完全不同
        CacheTemplate<String> stringCache = new CacheTemplate<>(new StringSerializer());
        stringCache.put("greeting", "你好 Spring");
        System.out.println("[String 策略] raw=" + new String(stringCache.rawBytes("greeting"), StandardCharsets.UTF_8));

        CacheTemplate<Order> jsonCache = new CacheTemplate<>(new JsonSerializer<>());
        jsonCache.put("order:1", new Order("O-10086", 39.9));
        System.out.println("[JSON 策略]   raw=" + new String(jsonCache.rawBytes("order:1"), StandardCharsets.UTF_8));
        System.out.println("[JSON 策略]   读回=" + jsonCache.get("order:1", Order.class));
    }
}
