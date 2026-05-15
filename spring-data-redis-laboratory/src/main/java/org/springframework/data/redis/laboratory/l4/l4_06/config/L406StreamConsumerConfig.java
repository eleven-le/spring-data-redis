package org.springframework.data.redis.laboratory.l4.l4_06.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 05.02 消费消息：StreamMessageListenerContainer
 *
 * 本配置类解决什么问题：
 *  - 把 StreamMessageListenerContainer 这个"消费基础设施"作为 Spring Bean 暴露出来，
 *    业务侧只需要 register 一个 Subscription，不再操心 BLOCK XREADGROUP 怎么写、连接怎么管、异常怎么兜底；
 *  - 选 MapRecord<String,String,String>，让消费者能自由把 Map 转成业务领域对象，
 *    而不是被框架硬绑定到一个 ObjectRecord<T> 的 T 上 —— 这点对多版本消息体兼容非常关键；
 *  - 显式配 errorHandler，避免某条 listener 抛异常之后整个 poll 循环静默死掉；
 *  - 显式配 executor，复用 {@link L406ExecutorConfig} 的命名线程池。
 *
 * 关键 Spring Data Redis API：
 *  - {@link StreamMessageListenerContainer#create(org.springframework.data.redis.connection.RedisConnectionFactory, StreamMessageListenerContainerOptions)}
 *  - {@code StreamMessageListenerContainer#receive(Consumer, StreamOffset, StreamListener)} —— 手动 ACK 入口
 *  - {@code StreamMessageListenerContainer#start()}
 *
 * 建议断点：
 *  - {@code StreamMessageListenerContainer#receive(...)} 看 Subscription 怎么被注册；
 *  - {@code StreamMessageListenerContainer#start()} 看 Subscription 怎么被 submit 到 executor；
 *  - 内部类 {@code StreamPollTask#doLoop / readRecords} 看每轮 BLOCK 拉取；
 *  - {@code RedisConnection#xReadGroup} 看真正下到 Lettuce 的命令。
 *
 * 可观察源码：
 *  - DefaultStreamMessageListenerContainer
 *  - StreamPollTask
 *  - DefaultStreamReceiver
 *  - LettuceStreamCommands#xReadGroup
 *
 * 新手避坑：
 *  - 不写 errorHandler：一旦 listener 抛异常，poll 任务静默退出，业务方"不收消息了"也不知道；
 *  - 写 receiveAutoAck：消息一拉到就 ACK，业务异常导致消息真实丢失；
 *  - pollTimeout 设 Duration.ZERO：变成 BLOCK 0 永久阻塞，应用关闭时 graceful shutdown 卡住；
 *  - batchSize 设 1：高并发下 N 倍 RTT 浪费，5～10 是经验起点。
 */
@Configuration
public class L406StreamConsumerConfig {

    /** 单批拉取条数：经验起点 10。看场景调；过大单条耗时长，过小 RTT 放大。 */
    private static final int BATCH_SIZE = 10;

    /** BLOCK 阻塞时间：2 秒。设短点，应用关闭时容器能尽快退出 poll 循环。 */
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);

    @Bean(initMethod = "start", destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>>
    streamMessageListenerContainer(LettuceConnectionFactory factory,
                                   @Qualifier("l406StreamExecutor") ThreadPoolExecutor executor) {

        StringRedisSerializer stringSerializer = StringRedisSerializer.UTF_8;

        // 偷师点：builder 上有一个便利方法 serializer(RedisSerializer<T>)，
        // 一次性把 keySerializer / hashKeySerializer / hashValueSerializer 都设成同一个
        // 并把 V 类型固定为 MapRecord<T, T, T>，避免逐个 setter 时的泛型推断坑。
        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .batchSize(BATCH_SIZE)
                        .pollTimeout(POLL_TIMEOUT)
                        .executor(executor)
                        // 全局 ErrorHandler：listener 抛异常 → 这里兜底打日志，
                        // 不要 throw 出去，否则 StreamPollTask 会终止 Subscription。
                        .errorHandler(throwable -> System.err.println(
                                "[L406][StreamPollTask][ErrorHandler] " + throwable))
                        .serializer(stringSerializer)
                        .build();

        return StreamMessageListenerContainer.create(factory, options);
    }
}
