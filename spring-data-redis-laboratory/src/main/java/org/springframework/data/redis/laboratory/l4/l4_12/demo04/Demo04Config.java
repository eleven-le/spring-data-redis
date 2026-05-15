package org.springframework.data.redis.laboratory.l4.l4_12.demo04;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.laboratory.l4.l4_12.common.L412RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;
import org.springframework.data.redis.laboratory.l4.l4_12.common.Topics;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.ErrorHandler;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * demo04：演示 listener 的"线程隔离 + 异常处理"两条生产硬要求。
 *
 * <p>关键 API：
 * - {@code container.setTaskExecutor(...)}: 业务派发线程池（执行 listener.onMessage）
 * - {@code container.setSubscriptionExecutor(...)}: 订阅线程（驱动 SUBSCRIBE 命令的 IO 线程）
 *   生产中两者强烈建议分开，避免业务慢拖垮订阅。
 * - {@code container.setErrorHandler(...)}: listener 异常统一回调。
 *
 * <p>线程池关键参数选择思路：
 * - 有界队列：避免突发广播打爆 JVM 内存
 * - CallerRunsPolicy：拒绝策略选回调 caller 线程，让"订阅线程"也帮忙跑业务，
 *   等价于背压；千万不要用 DiscardPolicy 默默丢消息。
 * - 命名前缀 biz-pool-：方便 jstack / 链路日志识别。
 */
@Configuration
@Import(L412RedisConfig.class)
public class Demo04Config {

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor pubSubBizExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(8);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("biz-pool-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.setKeepAliveSeconds(60);
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(5);
        exec.initialize();
        return exec;
    }

    /**
     * 生产 ErrorHandler：把异常转入业务监控通道。
     * 这里只打日志；真实项目应：埋点报警 + 业务侧补偿（例如下次定时对账）。
     */
    @Bean
    public ErrorHandler pubSubErrorHandler() {
        return throwable -> LogPrinter.print("ErrorHandler",
                "listener crashed: " + throwable.getClass().getSimpleName() +
                        " msg=" + throwable.getMessage());
    }

    @Bean
    public SlowBusinessListener slowListener() { return new SlowBusinessListener(800); }

    @Bean
    public ExceptionBusinessListener exceptionListener() { return new ExceptionBusinessListener(); }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory factory,
            ThreadPoolTaskExecutor pubSubBizExecutor,
            ErrorHandler pubSubErrorHandler,
            SlowBusinessListener slowListener,
            ExceptionBusinessListener exceptionListener) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        // 业务线程池：listener.onMessage 实际跑在这里
        container.setTaskExecutor(pubSubBizExecutor);
        // 订阅线程留给容器自己创建（它会用 createDefaultTaskExecutor 起一个 SimpleAsyncTaskExecutor）
        // 想完全解耦也可以再注入一个独立 subscriptionExecutor
        container.setErrorHandler(pubSubErrorHandler);
        container.afterPropertiesSet();

        container.addMessageListener(slowListener, new ChannelTopic(Topics.DEMO04_SLOW));
        container.addMessageListener(exceptionListener, new ChannelTopic(Topics.DEMO04_BOOM));
        return container;
    }
}
