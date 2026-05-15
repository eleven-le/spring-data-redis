package org.springframework.data.redis.laboratory.l4.l4_12.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.util.RedisConfigUtils;

/**
 * L4-12 章节通用 Spring 配置（非 Spring Boot）。
 *
 * <p>设计意图：
 * 1. 复用全局 RedisConfigUtils，避免每个 demo 重复 host/port/password；
 * 2. LettuceConnectionFactory 显式 destroyMethod，确保 ApplicationContext 关闭时
 *    Lettuce 客户端连接被释放——Pub/Sub 订阅会长期占用连接，泄漏风险比普通命令更高；
 * 3. 这里不直接 @Bean 出 RedisMessageListenerContainer，因为各 demo 关心的
 *    Topic 注册、Listener 数量、ErrorHandler、TaskExecutor 各不相同。
 *    每个 demo 自己在本地 @Configuration 里按需组装容器，把"容器组装"留给业务侧
 *    才能让你看清 RedisMessageListenerContainer 不是黑盒。
 */
@Configuration
public class L412RedisConfig {

    @Bean(destroyMethod = "destroy")
    public LettuceConnectionFactory lettuceConnectionFactory() {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration();
        cfg.setHostName(System.getProperty("redis.host", RedisConfigUtils.getHost()));
        cfg.setPort(Integer.parseInt(System.getProperty("redis.port", String.valueOf(RedisConfigUtils.getPort()))));
        cfg.setDatabase(RedisConfigUtils.getDatabase());

        String pwd = RedisConfigUtils.getPassword();
        if (pwd != null && !pwd.isEmpty()) {
            cfg.setPassword(RedisPassword.of(pwd));
        }
        return new LettuceConnectionFactory(cfg);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
