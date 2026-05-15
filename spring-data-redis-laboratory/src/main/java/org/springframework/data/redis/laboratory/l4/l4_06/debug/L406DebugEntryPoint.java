package org.springframework.data.redis.laboratory.l4.l4_06.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_06.L406Keys;
import org.springframework.data.redis.laboratory.l4.l4_06.config.L406ExecutorConfig;
import org.springframework.data.redis.laboratory.l4.l4_06.config.L406RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_06.config.L406StreamConsumerConfig;
import org.springframework.data.redis.laboratory.l4.l4_06.consumer.AnalyticsStreamConsumer;
import org.springframework.data.redis.laboratory.l4.l4_06.dlq.DeadLetterStreamPublisher;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEventPayload;
import org.springframework.data.redis.laboratory.l4.l4_06.idempotent.StreamConsumeIdempotentService;
import org.springframework.data.redis.laboratory.l4.l4_06.producer.OrderEventStreamProducer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.data.domain.Range;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 实战代码索引 → 调试路径
 *
 * 6 个独立 main，覆盖 Stream 学习中所有需要"独立断点跟踪"的关键点。
 * 每个方法的注释里写清楚断点位置 + 预期源码链路 + 观察重点。
 *
 * 通用建议：
 *  - 用 IDEA 把 spring-data-redis 2.7.18 sources 下载好；
 *  - View → Tool Windows → Threads，重点观察 sdr-l406-stream-poll-N 线程；
 *  - View → Conditional Breakpoint，按 group 名筛选断点，避免被无关消息打断。
 */
public class L406DebugEntryPoint {

    /**
     * 入口 1：debugAddMessage
     *
     * 目标：观察 XADD 路径。
     * 断点：
     *   - {@code DefaultStreamOperations#add(Record)}
     *   - {@code RedisTemplate#execute(RedisCallback, exposeConnection, pipeline)}
     *   - {@code RedisConnectionUtils#doGetConnection}
     *   - {@code LettuceStreamCommands#xAdd}
     * 观察：
     *   - serializer 选了哪个；
     *   - record 的 RecordId 是怎么由 Redis 生成回写；
     *   - byte[] body 长什么样。
     */
    public static void debugAddMessage() {
        try (AnnotationConfigApplicationContext ctx = newCtx(false)) {
            StringRedisTemplate redis = ctx.getBean(StringRedisTemplate.class);
            OrderEventStreamProducer producer = new OrderEventStreamProducer(redis);

            OrderEventPayload payload = new OrderEventPayload();
            payload.setSkuLines(List.of(new OrderEventPayload.SkuLine("SKU-X", 3, new BigDecimal("9.9"))));
            payload.setPayAmount(new BigDecimal("29.7"));

            RecordId rid = producer.publishCreated("ORD-DBG-1", "U-DBG-1", "SHOP-DBG", null, payload);
            System.out.println("[debugAddMessage] recordId = " + rid);
        }
    }

    /**
     * 入口 2：debugReadGroup
     *
     * 目标：观察 XREADGROUP 同步读取。
     * 断点：
     *   - {@code DefaultStreamOperations#read(Consumer, StreamReadOptions, StreamOffset...)}
     *   - {@code LettuceStreamCommands#xReadGroup}
     * 观察：
     *   - "&gt;" 偏移与 "0" 偏移的语义差异；
     *   - 返回的 List&lt;MapRecord&gt; 顺序；
     *   - 如何通过 CONSUMER 让 Redis 把读到的消息加入 PEL。
     */
    public static void debugReadGroup() {
        try (AnnotationConfigApplicationContext ctx = newCtx(false)) {
            StringRedisTemplate redis = ctx.getBean(StringRedisTemplate.class);
            ensureGroup(redis, L406Keys.GROUP_ANALYTICS);

            // 投一条让 group 有得读
            new OrderEventStreamProducer(redis).publishCreated(
                    "ORD-DBG-2", "U-DBG-2", "SHOP-DBG", null, samplePayload());

            StreamOperations<String, Object, Object> ops = redis.opsForStream();
            List<MapRecord<String, Object, Object>> records = ops.read(
                    Consumer.from(L406Keys.GROUP_ANALYTICS, L406Keys.consumer("analytics", "debug")),
                    StreamOffset.create(L406Keys.STREAM_ORDER_EVENT, ReadOffset.lastConsumed()));
            System.out.println("[debugReadGroup] records.size = " + (records == null ? 0 : records.size()));
        }
    }

    /**
     * 入口 3：debugAck
     *
     * 目标：观察 XACK。
     * 断点：
     *   - {@code DefaultStreamOperations#acknowledge(K, String, RecordId...)}
     *   - {@code LettuceStreamCommands#xAck}
     * 观察：
     *   - ACK 之后 PEL 是否真的减少（配合 debugPending 验证）。
     */
    public static void debugAck() {
        try (AnnotationConfigApplicationContext ctx = newCtx(false)) {
            StringRedisTemplate redis = ctx.getBean(StringRedisTemplate.class);
            ensureGroup(redis, L406Keys.GROUP_ANALYTICS);
            // 简化：先生产 → 读 → ACK，保证有东西可 ACK。
            new OrderEventStreamProducer(redis).publishCreated(
                    "ORD-DBG-3", "U-DBG-3", "SHOP-DBG", null, samplePayload());

            StreamOperations<String, Object, Object> ops = redis.opsForStream();
            List<MapRecord<String, Object, Object>> records = ops.read(
                    Consumer.from(L406Keys.GROUP_ANALYTICS, L406Keys.consumer("analytics", "debug")),
                    StreamOffset.create(L406Keys.STREAM_ORDER_EVENT, ReadOffset.lastConsumed()));
            if (records != null) {
                for (MapRecord<String, Object, Object> r : records) {
                    Long n = ops.acknowledge(L406Keys.STREAM_ORDER_EVENT, L406Keys.GROUP_ANALYTICS, r.getId());
                    System.out.println("[debugAck] ack id=" + r.getId() + " result=" + n);
                }
            }
        }
    }

    /**
     * 入口 4：debugPending
     *
     * 目标：观察 XPENDING summary 与详情。
     * 断点：
     *   - {@code DefaultStreamOperations#pending(K, String)}
     *   - {@code DefaultStreamOperations#pending(K, String, Range, long)}
     *   - {@code LettuceStreamCommands#xPending}
     * 观察：
     *   - 故意不 ACK 的消息，PEL 中的 idle 时间在不断增长；
     *   - PendingMessagesSummary 的 pendingMessagesPerConsumer 字段展示了哪些 consumer 持有消息。
     */
    public static void debugPending() {
        try (AnnotationConfigApplicationContext ctx = newCtx(false)) {
            StringRedisTemplate redis = ctx.getBean(StringRedisTemplate.class);
            StreamOperations<String, Object, Object> ops = redis.opsForStream();
            ensureGroup(redis, L406Keys.GROUP_ANALYTICS);

            // 故意读取但不 ACK，让消息进入 PEL。
            new OrderEventStreamProducer(redis).publishCreated(
                    "ORD-DBG-4", "U-DBG-4", "SHOP-DBG", null, samplePayload());
            ops.read(Consumer.from(L406Keys.GROUP_ANALYTICS, "consumer:analytics:pending-demo"),
                    StreamOffset.create(L406Keys.STREAM_ORDER_EVENT, ReadOffset.lastConsumed()));

            PendingMessagesSummary summary = ops.pending(L406Keys.STREAM_ORDER_EVENT, L406Keys.GROUP_ANALYTICS);
            System.out.println("[debugPending] summary = " + summary);

            PendingMessages detail = ops.pending(L406Keys.STREAM_ORDER_EVENT, L406Keys.GROUP_ANALYTICS,
                    Range.unbounded(), 50);
            for (PendingMessage pm : detail) {
                System.out.println("  pending = " + pm);
            }
        }
    }

    /**
     * 入口 5：debugAutoClaim（实际跑的是 XCLAIM —— SDR 2.7.x 没暴露 autoclaim 便利方法）
     *
     * 目标：观察 idle 超阈值消息被 XCLAIM 改变 owner。
     * 断点：
     *   - {@code DefaultStreamOperations#claim(K, String, String, Duration, RecordId...)}
     *   - {@code LettuceStreamCommands#xClaim}
     * 观察：
     *   - claim 之后再 XPENDING：原来的 owner 没有了，新 consumer 持有；
     *   - 服务端会用 minIdle 二次校验，避免 ABA 误抢。
     */
    public static void debugAutoClaim() throws Exception {
        try (AnnotationConfigApplicationContext ctx = newCtx(false)) {
            StringRedisTemplate redis = ctx.getBean(StringRedisTemplate.class);
            StreamOperations<String, Object, Object> ops = redis.opsForStream();
            ensureGroup(redis, L406Keys.GROUP_ANALYTICS);

            // 1. 用一个"假死"的 consumer 拉一条消息但不 ACK。
            new OrderEventStreamProducer(redis).publishCreated(
                    "ORD-DBG-5", "U-DBG-5", "SHOP-DBG", null, samplePayload());
            String dyingConsumer = "consumer:analytics:dying";
            ops.read(Consumer.from(L406Keys.GROUP_ANALYTICS, dyingConsumer),
                    StreamOffset.create(L406Keys.STREAM_ORDER_EVENT, ReadOffset.lastConsumed()));

            // 2. 等 idle 跑过阈值（这里用 1 秒做演示，生产用分钟级）。
            Thread.sleep(1500);

            // 3. 用一个"恢复"消费者把它 claim 过来。
            PendingMessages pendings = ops.pending(L406Keys.STREAM_ORDER_EVENT, L406Keys.GROUP_ANALYTICS,
                    Range.unbounded(), 10);
            RecordId[] ids = new RecordId[pendings.size()];
            for (int i = 0; i < pendings.size(); i++) {
                ids[i] = pendings.get(i).getId();
            }
            if (ids.length > 0) {
                List<MapRecord<String, Object, Object>> claimed = ops.claim(L406Keys.STREAM_ORDER_EVENT,
                        L406Keys.GROUP_ANALYTICS,
                        "consumer:analytics:rescue",
                        Duration.ofSeconds(1),
                        ids);
                System.out.println("[debugAutoClaim] claimed.size = " + claimed.size());
            }
        }
    }

    /**
     * 入口 6：debugListenerContainer
     *
     * 目标：观察 StreamMessageListenerContainer 的整体生命周期。
     * 断点：
     *   - {@code StreamMessageListenerContainer#receive}
     *   - {@code StreamMessageListenerContainer#start}
     *   - {@code StreamPollTask#doLoop / readRecords}
     *   - {@link AnalyticsStreamConsumer#handleEvent}
     * 观察：
     *   - poll 任务如何在线程池上 submit；
     *   - listener 怎么被异步唤起；
     *   - poll 失败时 errorHandler 真的接住了；
     *   - subscription.cancel() 之后 poll 任务停下。
     */
    public static void debugListenerContainer() throws Exception {
        try (AnnotationConfigApplicationContext ctx = newCtx(true)) {
            StringRedisTemplate redis = ctx.getBean(StringRedisTemplate.class);
            ensureGroup(redis, L406Keys.GROUP_ANALYTICS);

            StreamConsumeIdempotentService idem = new StreamConsumeIdempotentService(redis);
            DeadLetterStreamPublisher dlq = new DeadLetterStreamPublisher(redis);
            AnalyticsStreamConsumer analytics = new AnalyticsStreamConsumer(redis, idem, dlq,
                    L406Keys.consumer("analytics", "debug"));

            @SuppressWarnings("unchecked")
            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                    (StreamMessageListenerContainer<String, MapRecord<String, String, String>>)
                            ctx.getBean(StreamMessageListenerContainer.class);

            Subscription sub = container.receive(
                    Consumer.from(L406Keys.GROUP_ANALYTICS, analytics.getConsumerName()),
                    StreamOffset.create(L406Keys.STREAM_ORDER_EVENT, ReadOffset.lastConsumed()),
                    analytics);
            sub.await(Duration.ofSeconds(5));

            new OrderEventStreamProducer(redis).publishCreated(
                    "ORD-DBG-6", "U-DBG-6", "SHOP-DBG", null, samplePayload());

            Thread.sleep(3000);
            sub.cancel();
            System.out.println("[debugListenerContainer] subscription cancelled");
        }
    }

    /**
     * 主入口：根据 args[0] 选择跑哪个 debug。
     * 没传参数则跑全部。
     */
    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "all" : args[0];
        switch (mode) {
            case "add" -> debugAddMessage();
            case "read" -> debugReadGroup();
            case "ack" -> debugAck();
            case "pending" -> debugPending();
            case "claim" -> debugAutoClaim();
            case "container" -> debugListenerContainer();
            case "all" -> {
                debugAddMessage();
                debugReadGroup();
                debugAck();
                debugPending();
                debugAutoClaim();
                debugListenerContainer();
            }
            default -> System.out.println("usage: L406DebugEntryPoint [add|read|ack|pending|claim|container|all]");
        }
    }

    private static AnnotationConfigApplicationContext newCtx(boolean withListenerContainer) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(L406RedisConfig.class);
        if (withListenerContainer) {
            ctx.register(L406ExecutorConfig.class);
            ctx.register(L406StreamConsumerConfig.class);
        }
        ctx.refresh();
        return ctx;
    }

    private static void ensureGroup(StringRedisTemplate redis, String group) {
        try {
            redis.opsForStream().createGroup(L406Keys.STREAM_ORDER_EVENT, ReadOffset.from("0"), group);
        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            if (!msg.contains("BUSYGROUP")) {
                throw ex;
            }
        }
    }

    private static OrderEventPayload samplePayload() {
        OrderEventPayload p = new OrderEventPayload();
        p.setSkuLines(List.of(new OrderEventPayload.SkuLine(
                "SKU-DBG-" + UUID.randomUUID().toString().substring(0, 4),
                1, new BigDecimal("9.9"))));
        p.setPayAmount(new BigDecimal("9.9"));
        p.setClientIp("127.0.0.1");
        p.setDeviceId("dev-debug");
        return p;
    }
}
