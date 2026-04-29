package org.springframework.data.redis.laboratory.l3_03.toushi;

import java.util.function.Function;

/**
 * 偷师 LettuceConnectionProvider 的"专用连接"路径：
 *   - 不共享，每次按 groupId 新建
 *   - 调用方负责 close（订阅完成后释放）
 * <p>
 * 为什么 Consumer 不能像 Producer 那样共享？
 *   - 订阅状态、消费位点、心跳都是 *连接级* 状态
 *   - 多个订阅复用一条连接，会出现"位点错位"、"reblance 风暴"、"连接被某个慢 listener 卡死"
 *   - 这跟 Lettuce 不让 Pub/Sub 走共享连接是同一个根因
 */
public class DedicatedConsumerProvider {

    private final Function<String, MqConsumer> consumerFactory;

    public DedicatedConsumerProvider(Function<String, MqConsumer> consumerFactory) {
        this.consumerFactory = consumerFactory;
    }

    public MqConsumer create(String groupId) {
        return consumerFactory.apply(groupId);
    }
}
