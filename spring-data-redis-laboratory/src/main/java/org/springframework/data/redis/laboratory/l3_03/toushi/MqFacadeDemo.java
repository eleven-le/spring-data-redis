package org.springframework.data.redis.laboratory.l3_03.toushi;

/**
 * 偷师案例驱动类：跑一遍"业务方完全不感知 MQ 厂商"的体验。
 * <p>
 * 业务侧的代码（getSharedProducer().send(...)）不用任何 RocketMQ / Kafka SDK，
 * 切换工厂就完成了 MQ 切换 —— 这跟 Spring Data Redis 切换 Jedis / Lettuce
 * 是一模一样的设计套路：
 *   - 工厂收口创建（Factory）
 *   - Provider 决定共享 / 专用（Strategy）
 *   - 业务接口适配多个 SDK（Adapter）
 *   - 共享 Producer 单例（Singleton）
 */
public class MqFacadeDemo {

    public static void main(String[] args) {
        // 假设运维今天用 RocketMQ
        //MqClientFactory factory = new RocketMqClientFactory("ns.example.com:9876");
        // 明天换 Kafka，业务代码一行不动：
        MqClientFactory factory = new KafkaMqClientFactory("kafka:9092");

        factory.start();

        // ① 共享生产者 —— 高并发发券消息走这里
        MqProducer p1 = factory.getSharedProducer();
        MqProducer p2 = factory.getSharedProducer();
        System.out.println("共享生产者是同一个实例? " + (p1 == p2));
        p1.send("coupon-grant", "userId-123", "{\"couponId\":42}".getBytes());

        // ② 专用消费者 —— 库存扣减监听走这里
        MqConsumer c1 = factory.createDedicatedConsumer("inventory-deduct-group");
        MqConsumer c2 = factory.createDedicatedConsumer("order-timeout-group");
        System.out.println("专用消费者是同一个实例? " + (c1 == c2));
        c1.subscribe("inventory.deduct.topic", payload -> {});
        c2.subscribe("order.timeout.topic", payload -> {});

        c1.close();
        c2.close();
        factory.shutdown();
    }
}
