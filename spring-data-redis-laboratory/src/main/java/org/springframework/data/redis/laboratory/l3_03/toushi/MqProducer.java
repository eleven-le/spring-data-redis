package org.springframework.data.redis.laboratory.l3_03.toushi;

/**
 * 偷师 LettuceConnection 的 RedisConnection 适配层：
 * 业务统一面对 MqProducer 接口，不直接依赖 RocketMQ / Kafka SDK 的具体类型。
 * <p>
 * 核心收益：
 *   - 切换 MQ 实现只改装配，业务代码零改动
 *   - 测试可以用 InMemoryMqProducer
 *   - 跨 MQ 的统一 metric / trace / 重试封装，可以下沉到适配层
 */
public interface MqProducer {

    /** 同步发送，等服务端 ack */
    void send(String topic, String key, byte[] payload);

    /** 异步发送，返回轻量 SendResult 或 future（这里简化为 void+回调钩子） */
    void sendAsync(String topic, String key, byte[] payload, SendCallback cb);

    /** 资源释放：可能是连接 close、producer shutdown 等 */
    void close();

    interface SendCallback {
        void onSuccess(String msgId);
        void onFailure(Throwable t);
    }
}
