package org.springframework.data.redis.laboratory.l3_03.toushi;

import java.util.function.Consumer;

/**
 * 消费端门面。注意：
 *   - 不像 Producer 可以共享，Consumer 通常需要自己的"专用 channel"
 *     （订阅状态、心跳、消费位点都是连接级状态）
 *   - 这就是为什么 Lettuce 的 Pub/Sub 一定要用 dedicated connection
 */
public interface MqConsumer extends AutoCloseable {

    void subscribe(String topic, Consumer<byte[]> handler);

    void unsubscribe(String topic);

    @Override
    void close();
}
