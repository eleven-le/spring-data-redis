package org.springframework.data.redis.laboratory.l3_03.toushi;

import java.util.function.Consumer;

/**
 * Kafka 适配实现（演示骨架）。
 * <p>
 * 偷师点：
 *   - KafkaProducer 本身就是线程安全的，跟 StatefulRedisConnection 同款思路：
 *     一个进程共享一个 Producer，由内部 IO 线程批量异步发送
 *   - KafkaConsumer 不是线程安全的，每个 group/listener 必须独占
 *     这恰好对应 LettuceConnection 把 Pub/Sub 推到 dedicated connection 的设计
 */
public class KafkaMqClientFactory implements MqClientFactory {

    private final String bootstrapServers;
    private final SharedProducerProvider sharedProducerProvider;
    private final DedicatedConsumerProvider dedicatedConsumerProvider;
    private volatile boolean started = false;

    public KafkaMqClientFactory(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
        this.sharedProducerProvider = new SharedProducerProvider(InMemoryProducer::new);
        this.dedicatedConsumerProvider = new DedicatedConsumerProvider(InMemoryConsumer::new);
    }

    @Override
    public void start() {
        System.out.println("[kafka-factory] 启动，bootstrapServers=" + bootstrapServers);
        started = true;
    }

    @Override
    public MqProducer getSharedProducer() {
        if (!started) throw new IllegalStateException("factory not started");
        return sharedProducerProvider.get();
    }

    @Override
    public MqConsumer createDedicatedConsumer(String groupId) {
        if (!started) throw new IllegalStateException("factory not started");
        return dedicatedConsumerProvider.create(groupId);
    }

    @Override
    public void shutdown() {
        sharedProducerProvider.close();
        started = false;
    }

    static class InMemoryProducer implements MqProducer {
        @Override public void send(String topic, String key, byte[] payload) {
            System.out.printf("[mock-kafka][send] topic=%s key=%s bytes=%d%n", topic, key, payload.length);
        }
        @Override public void sendAsync(String topic, String key, byte[] payload, SendCallback cb) {
            cb.onSuccess("mock-kafka-offset");
        }
        @Override public void close() { /* nop */ }
    }

    static class InMemoryConsumer implements MqConsumer {
        private final String groupId;
        InMemoryConsumer(String groupId) { this.groupId = groupId; }
        @Override public void subscribe(String topic, Consumer<byte[]> handler) {
            System.out.printf("[mock-kafka][subscribe] group=%s topic=%s%n", groupId, topic);
        }
        @Override public void unsubscribe(String topic) { /* nop */ }
        @Override public void close() { /* nop */ }
    }
}
