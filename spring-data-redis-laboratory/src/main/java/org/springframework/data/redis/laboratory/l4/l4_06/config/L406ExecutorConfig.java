package org.springframework.data.redis.laboratory.l4.l4_06.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 04 Spring Data Redis 对 Stream 的抽象 → StreamMessageListenerContainerOptions
 *
 * 本配置类解决什么问题：
 *  - StreamMessageListenerContainer 的 executor 不显式配，会用单线程默认池，
 *    多 group 并发拉取时极易排队，C 端订单事件场景几个消费者一起跑就拥堵；
 *  - 单独一个 listener 抛异常如果占用主消费线程，会卡住整个容器的 poll，
 *    必须用足够的工作线程兜底；
 *  - 自己定义 ThreadFactory，把线程命名为 sdr-l406-stream-poll-N，
 *    线上排查 dump 时一眼能看出是 Stream 消费线程。
 *
 * 关键 Spring Data Redis API：
 *  - {@code StreamMessageListenerContainerOptions.builder().executor(Executor)}
 *  - 内部由 {@code StreamPollTask} 在该 executor 上 submit 阻塞拉取任务
 *
 * 建议断点：
 *  - {@code StreamMessageListenerContainer#start} 看每条 Subscription 是怎么被 submit 到 executor 的；
 *  - {@code StreamPollTask#run} 看消息读取与分派是不是真的跑在这里命名好的线程上。
 *
 * 新手避坑：
 *  - 池子开太小：N 个 group 同时阻塞 BLOCK 读，没空闲线程就只能轮流；
 *  - 池子开太大：阻塞 XREAD 占用 native 连接，连接池被打爆；
 *  - 不设 ThreadFactory：dump 出来 pool-1-thread-3 谁也分不清是哪个组件的池；
 *  - 默认 LinkedBlockingQueue 是无界的，listener 卡住会撑爆堆，本配置故意限定容量。
 */
@Configuration
public class L406ExecutorConfig {

    /** 5 个业务 group + Pending 恢复 + 监控 上报 + 突发余量 → 起步 8。 */
    private static final int CORE_POOL_SIZE = 8;
    private static final int MAX_POOL_SIZE = 16;
    private static final long KEEP_ALIVE_SECONDS = 60L;
    private static final int QUEUE_CAPACITY = 256;

    @Bean(name = "l406StreamExecutor", destroyMethod = "shutdownNow")
    public ThreadPoolExecutor l406StreamExecutor() {
        return new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                namedThreadFactory("sdr-l406-stream-poll"),
                // 池满拒绝策略选 CallerRunsPolicy：
                // 让生产/调度线程自己跑，自然反压回上游，
                // 比抛异常吞掉消息更适合"不能丢"的事件流场景。
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
