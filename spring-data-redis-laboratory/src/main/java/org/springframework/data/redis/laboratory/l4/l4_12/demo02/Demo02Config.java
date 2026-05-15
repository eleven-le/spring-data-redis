package org.springframework.data.redis.laboratory.l4.l4_12.demo02;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_12.common.L412RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_12.common.Topics;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * demo02 配置：模拟两个节点（Node-A、Node-B）。
 *
 * <p>实现技巧：在同一个 JVM 里"模拟"多节点，办法是给两个节点
 * 各自一份 LocalProductCache + 一个 listener 实例，但共享 ProductRepository
 * （因为主数据是全局事实）。在真实系统里 Node-A / Node-B 是两个进程，
 * 各自有 RedisMessageListenerContainer，连同一个 Redis、订阅同一个 channel。
 *
 * <p>同一个 channel 注册两个 listener，容器会通过 channelMapping 查找并各自派发，
 * 模拟"两个进程都收到广播"的真实效果。
 */
@Configuration
@Import(L412RedisConfig.class)
public class Demo02Config {

    @Bean
    public ProductRepository productRepository() {
        ProductRepository repo = new ProductRepository();
        repo.seed(1001L, 88L, "黑椒牛肉饭", 2800);
        return repo;
    }

    @Bean
    public LocalProductCache cacheNodeA() { return new LocalProductCache("A"); }

    @Bean
    public LocalProductCache cacheNodeB() { return new LocalProductCache("B"); }

    @Bean
    public ProductCacheRefreshListener listenerA(LocalProductCache cacheNodeA, ProductRepository repo) {
        return new ProductCacheRefreshListener(cacheNodeA, repo);
    }

    @Bean
    public ProductCacheRefreshListener listenerB(LocalProductCache cacheNodeB, ProductRepository repo) {
        return new ProductCacheRefreshListener(cacheNodeB, repo);
    }

    @Bean
    public ProductCacheRefreshPublisher publisher(StringRedisTemplate template) {
        return new ProductCacheRefreshPublisher(template);
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory factory,
            ProductCacheRefreshListener listenerA,
            ProductCacheRefreshListener listenerB) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.afterPropertiesSet();
        ChannelTopic topic = new ChannelTopic(Topics.PRODUCT_CHANGED);
        container.addMessageListener(listenerA, topic);
        container.addMessageListener(listenerB, topic);
        return container;
    }
}
