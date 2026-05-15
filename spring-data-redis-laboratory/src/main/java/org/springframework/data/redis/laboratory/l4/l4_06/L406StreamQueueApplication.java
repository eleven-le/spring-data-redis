package org.springframework.data.redis.laboratory.l4.l4_06;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_06.config.L406ExecutorConfig;
import org.springframework.data.redis.laboratory.l4.l4_06.config.L406RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_06.config.L406StreamConsumerConfig;
import org.springframework.data.redis.laboratory.l4.l4_06.consumer.AnalyticsStreamConsumer;
import org.springframework.data.redis.laboratory.l4.l4_06.consumer.CouponWriteOffStreamConsumer;
import org.springframework.data.redis.laboratory.l4.l4_06.consumer.InventoryReserveStreamConsumer;
import org.springframework.data.redis.laboratory.l4.l4_06.consumer.NotifyUserStreamConsumer;
import org.springframework.data.redis.laboratory.l4.l4_06.consumer.RiskCheckStreamConsumer;
import org.springframework.data.redis.laboratory.l4.l4_06.dlq.DeadLetterStreamPublisher;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEventPayload;
import org.springframework.data.redis.laboratory.l4.l4_06.idempotent.StreamConsumeIdempotentService;
import org.springframework.data.redis.laboratory.l4.l4_06.monitor.StreamQueueMetricsReporter;
import org.springframework.data.redis.laboratory.l4.l4_06.producer.OrderEventStreamProducer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 实战代码索引
 *
 * 整套 L4-06 实验的启动入口。流程：
 *   1. AnnotationConfigApplicationContext 装配所有 Bean；
 *   2. 5 个 group 用 MKSTREAM 创建（已存在则吞掉 BUSYGROUP）；
 *   3. 5 个 listener 各注册到容器（手动 ACK）；
 *   4. 生产几条订单事件，观察 5 个 group 的消费输出；
 *   5. 打印一次监控快照，证明指标正常；
 *   6. 进程保持运行 60s，方便断点跟踪。
 *
 * 建议断点：
 *  - {@link StreamMessageListenerContainer#receive} —— 看 Subscription 注册过程；
 *  - {@link StreamMessageListenerContainer#start} —— 看 poll task submit；
 *  - 任意 listener.onMessage —— 进入业务路径；
 *  - {@code StreamPollTask#doLoop / readRecords} —— 阻塞拉取；
 *  - {@code DefaultStreamOperations#acknowledge} —— ACK 真实下发。
 *
 * 新手避坑：
 *  - 没用 MKSTREAM 直接 createGroup 会因为 stream 不存在抛错；
 *  - 启动后立刻退出，主线程结束 → 容器还没拉到消息进程就死；
 *  - 多次运行没处理 BUSYGROUP 错误 → 二次启动 silent fail；
 *  - 同一 consumerName 多实例并行启动 → pending 归属混乱，排错困难。
 */
public class L406StreamQueueApplication {

    public static void main(String[] args) throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(L406RedisConfig.class,
                    L406ExecutorConfig.class,
                    L406StreamConsumerConfig.class);
            ctx.refresh();
            // ↑ 容器 refresh 之后 StreamMessageListenerContainer Bean 已经 start（initMethod=start）。

            StringRedisTemplate redis = ctx.getBean(StringRedisTemplate.class);
            ensureGroups(redis);

            // 业务组件 ApplicationContext 不会自动扫描 @Component（没用 ComponentScan），
            // 所以这里手动 new。这样做的好处：每个 Bean 的协作关系一目了然，
            // 适合源码学习场景。生产里直接用 @ComponentScan 即可。
            StreamConsumeIdempotentService idempotent = new StreamConsumeIdempotentService(redis);
            DeadLetterStreamPublisher dlq = new DeadLetterStreamPublisher(redis);
            OrderEventStreamProducer producer = new OrderEventStreamProducer(redis);
            StreamQueueMetricsReporter reporter = new StreamQueueMetricsReporter(redis);

            // 5 个 listener，每个对应一个 group，consumerName 用机器名 + 进程标识，本实验简化为固定串。
            InventoryReserveStreamConsumer inventory = new InventoryReserveStreamConsumer(
                    redis, idempotent, dlq, L406Keys.consumer("inventory", "01"));
            CouponWriteOffStreamConsumer coupon = new CouponWriteOffStreamConsumer(
                    redis, idempotent, dlq, L406Keys.consumer("coupon", "01"));
            NotifyUserStreamConsumer notify = new NotifyUserStreamConsumer(
                    redis, idempotent, dlq, L406Keys.consumer("notify", "01"));
            RiskCheckStreamConsumer risk = new RiskCheckStreamConsumer(
                    redis, idempotent, dlq, L406Keys.consumer("risk", "01"));
            AnalyticsStreamConsumer analytics = new AnalyticsStreamConsumer(
                    redis, idempotent, dlq, L406Keys.consumer("analytics", "01"));

            @SuppressWarnings("unchecked")
            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                    (StreamMessageListenerContainer<String, MapRecord<String, String, String>>)
                            ctx.getBean(StreamMessageListenerContainer.class);

            Subscription subInv = container.receive(
                    Consumer.from(L406Keys.GROUP_INVENTORY, inventory.getConsumerName()),
                    StreamOffset.create(L406Keys.STREAM_ORDER_EVENT, ReadOffset.lastConsumed()),
                    inventory);
            Subscription subCoupon = container.receive(
                    Consumer.from(L406Keys.GROUP_COUPON, coupon.getConsumerName()),
                    StreamOffset.create(L406Keys.STREAM_ORDER_EVENT, ReadOffset.lastConsumed()),
                    coupon);
            Subscription subNotify = container.receive(
                    Consumer.from(L406Keys.GROUP_NOTIFY, notify.getConsumerName()),
                    StreamOffset.create(L406Keys.STREAM_ORDER_EVENT, ReadOffset.lastConsumed()),
                    notify);
            Subscription subRisk = container.receive(
                    Consumer.from(L406Keys.GROUP_RISK, risk.getConsumerName()),
                    StreamOffset.create(L406Keys.STREAM_ORDER_EVENT, ReadOffset.lastConsumed()),
                    risk);
            Subscription subAna = container.receive(
                    Consumer.from(L406Keys.GROUP_ANALYTICS, analytics.getConsumerName()),
                    StreamOffset.create(L406Keys.STREAM_ORDER_EVENT, ReadOffset.lastConsumed()),
                    analytics);

            for (Subscription s : List.of(subInv, subCoupon, subNotify, subRisk, subAna)) {
                s.await(java.time.Duration.ofSeconds(5));
            }
            System.out.println("[L406] 5 个 Subscription 已就绪，开始生产测试事件…");

            String orderId = "ORD-" + UUID.randomUUID();
            OrderEventPayload payload = new OrderEventPayload();
            payload.setSkuLines(List.of(
                    new OrderEventPayload.SkuLine("SKU-A", 2, new BigDecimal("12.50")),
                    new OrderEventPayload.SkuLine("SKU-B", 1, new BigDecimal("28.00"))));
            payload.setCouponIds(List.of("CPN-100", "CPN-001"));
            payload.setPayAmount(new BigDecimal("53.00"));
            payload.setCouponDiscountAmount(new BigDecimal("5.00"));
            payload.setPayChannel("WECHAT");
            payload.setClientIp("203.0.113.42");
            payload.setDeviceId("dev-iphone-13-pro");
            payload.setClientVer("3.7.1");
            payload.setUtmSource("home_banner");

            producer.publishCreated(orderId, "U001", "SHOP01", null, payload);
            producer.publishCouponApplied(orderId, "U001", "SHOP01", null, payload);
            producer.publishPaid(orderId, "U001", "SHOP01", null, payload);

            // 给消费者一点时间跑完，再打监控快照。
            Thread.sleep(3000);
            reporter.printSnapshot();

            System.out.println("[L406] 应用保持运行 60s，便于断点跟踪。Ctrl+C 退出。");
            Thread.sleep(60_000);
        }
    }

    /** 5 个 group 都用 MKSTREAM 创建；已存在的 group 会抛 BUSYGROUP，安全吞掉。 */
    private static void ensureGroups(StringRedisTemplate redis) {
        for (String group : List.of(
                L406Keys.GROUP_INVENTORY,
                L406Keys.GROUP_COUPON,
                L406Keys.GROUP_NOTIFY,
                L406Keys.GROUP_RISK,
                L406Keys.GROUP_ANALYTICS)) {
            try {
                redis.opsForStream().createGroup(L406Keys.STREAM_ORDER_EVENT,
                        ReadOffset.from("0"), group);
                System.out.println("[L406] 创建 group " + group);
            } catch (Exception ex) {
                String msg = ex.getMessage() == null ? "" : ex.getMessage();
                if (msg.contains("BUSYGROUP")) {
                    System.out.println("[L406] group 已存在: " + group);
                } else {
                    throw ex;
                }
            }
        }
    }
}
