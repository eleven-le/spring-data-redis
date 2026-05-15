package org.springframework.data.redis.laboratory.l4.l4_12.demo05;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.laboratory.l4.l4_12.common.L412RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_12.common.Topics;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * demo05 配置：仅启动 Node-A 的容器；Node-B 的容器在 main 里手动延后启动，
 * 模拟"广播发出时 B 还没在线"的场景。
 */
@Configuration
@Import(L412RedisConfig.class)
public class Demo05Config {

    @Bean
    public ConfigStore configStore() { return new ConfigStore(); }

    @Bean
    public NodeLocalConfigCache cacheNodeA() { return new NodeLocalConfigCache("A"); }

    @Bean
    public ConfigChangedListener listenerA(NodeLocalConfigCache cacheNodeA, ConfigStore configStore) {
        return new ConfigChangedListener(cacheNodeA, configStore);
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory factory, ConfigChangedListener listenerA) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.afterPropertiesSet();
        container.addMessageListener(listenerA, new ChannelTopic(Topics.DEMO05_CONFIG_CHANGED));
        return container;
    }
}
