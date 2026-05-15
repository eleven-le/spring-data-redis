package org.springframework.data.redis.laboratory.l4.l4_12.demo01;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.laboratory.l4.l4_12.common.L412RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_12.common.Topics;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * demo01 配置：组装一个最小可运行的 RedisMessageListenerContainer。
 *
 * <p>关键设计意图：
 * <ol>
 *   <li><b>为什么需要容器</b>：原生 Redis SUBSCRIBE 一旦发出，连接就进入 subscribed 状态，
 *       不能再执行普通命令；如果你自己手写 listener，要管理：连接、订阅线程、反订阅、
 *       重连、listener 注册表、生命周期……容器把这些都封了。</li>
 *   <li><b>不显式调 start()</b>：RedisMessageListenerContainer 实现了 SmartLifecycle，
 *       默认 autoStartup=true，ApplicationContext refresh 完成后由 Spring 自动 start。
 *       这是它能融入 Spring 容器的关键——它不是工具类，是生命周期 Bean。</li>
 *   <li><b>destroyMethod 不需要显式声明</b>：实现了 DisposableBean，Spring 自动调 destroy()，
 *       内部会 stop() 并释放订阅连接。</li>
 * </ol>
 */
@Configuration
@Import(L412RedisConfig.class)
public class Demo01Config {

    /**
     * 容器 Bean 本身。这里不调 start()，让 SmartLifecycle 接管启动时机。
     * 添加 listener 必须在 afterPropertiesSet 之前用 setMessageListeners()，或在
     * 之后用 addMessageListener()——本 demo 选后者，更接近实际业务（运行时动态加 listener）。
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory factory, HelloListener helloListener) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        // afterPropertiesSet 由 Spring 在 init 阶段调用——内部会 createDefaultTaskExecutor()
        // 创建 SimpleAsyncTaskExecutor。本 demo 不自定义线程池，看默认行为。
        container.afterPropertiesSet();

        // 注册 listener。注意：这里没有 start()，但 addMessageListener 内部如果发现
        // afterPropertiesSet=true 且 started=true 会触发 lazyListen()。
        // 因为 SmartLifecycle.start() 还没被调用，此时只是把 listener 入注册表。
        container.addMessageListener(helloListener, new ChannelTopic(Topics.DEMO01_HELLO));
        return container;
    }

    @Bean
    public HelloListener helloListener() {
        return new HelloListener();
    }
}
