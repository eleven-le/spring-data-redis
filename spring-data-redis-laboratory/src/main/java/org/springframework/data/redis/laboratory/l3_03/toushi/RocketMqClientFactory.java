package org.springframework.data.redis.laboratory.l3_03.toushi;

import java.util.function.Consumer;

/**
 * RocketMQ 适配实现（演示骨架，*不真实连接* RocketMQ）。
 * <p>
 * 偷师点：
 *   - 整个 JVM 共享一个 DefaultMQProducer（对标 StatefulRedisConnection）
 *     RocketMQ 官方文档明确建议：进程级别只创建少量 Producer 实例，按业务隔离
 *   - 每个 Consumer 独立一份 DefaultMQPushConsumer（对标 dedicated connection）
 *   - 工厂在 start() 时统一拉起，shutdown() 时统一回收
 */
public class RocketMqClientFactory implements MqClientFactory {

    private final String nameServer;
    private final SharedProducerProvider sharedProducerProvider;
    private final DedicatedConsumerProvider dedicatedConsumerProvider;
    private volatile boolean started = false;

    public RocketMqClientFactory(String nameServer) {
        this.nameServer = nameServer;
        // 真实代码这里会 new DefaultMQProducer(...)，本骨架用 InMemoryProducer 顶替
        this.sharedProducerProvider = new SharedProducerProvider(InMemoryProducer::new);
        this.dedicatedConsumerProvider = new DedicatedConsumerProvider(InMemoryConsumer::new);
    }

    @Override
    public void start() {
        System.out.println("[rocketmq-factory] 启动，nameServer=" + nameServer);
        started = true;
    }

    @Override
    public MqProducer getSharedProducer() {
        ensureStarted();
        return sharedProducerProvider.get();
    }

    @Override
    public MqConsumer createDedicatedConsumer(String groupId) {
        ensureStarted();
        return dedicatedConsumerProvider.create(groupId);
    }

    @Override
    public void shutdown() {
        sharedProducerProvider.close();
        started = false;
    }

    private void ensureStarted() {
        if (!started) throw new IllegalStateException("factory not started");
    }

    // ──────────── 以下是骨架级"假实现"，真实场景换成 RocketMQ SDK ────────────

    static class InMemoryProducer implements MqProducer {
        @Override public void send(String topic, String key, byte[] payload) {
            System.out.printf("[mock-rocketmq][send] topic=%s key=%s bytes=%d%n", topic, key, payload.length);
        }
        @Override public void sendAsync(String topic, String key, byte[] payload, SendCallback cb) {
            cb.onSuccess("mock-msg-id");
        }
        @Override public void close() { /* nop */ }
    }

    static class InMemoryConsumer implements MqConsumer {
        private final String groupId;
        InMemoryConsumer(String groupId) { this.groupId = groupId; }
        @Override public void subscribe(String topic, Consumer<byte[]> handler) {
            System.out.printf("[mock-rocketmq][subscribe] group=%s topic=%s%n", groupId, topic);
        }
        @Override public void unsubscribe(String topic) { /* nop */ }
        @Override public void close() { /* nop */ }
    }
}
