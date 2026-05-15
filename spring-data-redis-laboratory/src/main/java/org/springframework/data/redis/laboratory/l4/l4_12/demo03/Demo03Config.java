package org.springframework.data.redis.laboratory.l4.l4_12.demo03;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.laboratory.l4.l4_12.common.L412RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_12.common.Topics;
import org.springframework.data.redis.laboratory.l4.l4_12.demo02.LocalProductCache;
import org.springframework.data.redis.laboratory.l4.l4_12.demo02.ProductCacheRefreshListener;
import org.springframework.data.redis.laboratory.l4.l4_12.demo02.ProductRepository;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * demo03：演示同一个 RedisMessageListenerContainer 同时管理：
 * - 多个 ChannelTopic 精准订阅
 * - 一个 PatternTopic 模式订阅
 * - 同一个 listener 监听多个 topic 的能力（通过多次 addMessageListener 实现）
 *
 * <p>容器内部维护三张表（见源码 patternMapping / channelMapping / listenerTopics），
 * 因此 add/remove 都是 O(1) 的注册表操作。本 demo 让你看清这一点。
 */
@Configuration
@Import(L412RedisConfig.class)
public class Demo03Config {

    @Bean
    public ProductRepository productRepository() {
        ProductRepository repo = new ProductRepository();
        repo.seed(1001L, 88L, "黑椒牛肉饭", 2800);
        return repo;
    }

    @Bean
    public LocalProductCache productCache() { return new LocalProductCache("D3"); }

    @Bean
    public ProductCacheRefreshListener productListener(LocalProductCache productCache, ProductRepository repo) {
        return new ProductCacheRefreshListener(productCache, repo);
    }

    @Bean
    public UserStatusListener userStatusListener() { return new UserStatusListener(); }

    @Bean
    public ConfigChangedListener configChangedListener() { return new ConfigChangedListener(); }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory factory,
            ProductCacheRefreshListener productListener,
            UserStatusListener userStatusListener,
            ConfigChangedListener configChangedListener) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.afterPropertiesSet();

        container.addMessageListener(productListener, new ChannelTopic(Topics.PRODUCT_CHANGED));
        container.addMessageListener(userStatusListener, new ChannelTopic(Topics.USER_STATUS_CHANGED));
        // PatternTopic：匹配 lab.l412.config.* 下所有子频道（city / risk / delivery）
        container.addMessageListener(configChangedListener, new PatternTopic(Topics.CONFIG_PATTERN));

        return container;
    }
}
