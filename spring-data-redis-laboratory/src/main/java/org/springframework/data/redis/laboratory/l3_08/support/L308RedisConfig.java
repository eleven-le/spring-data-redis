package org.springframework.data.redis.laboratory.l3_08.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.util.RedisConfigUtils;

/**
 * <h3>L3-08 共享连接版配置(默认 share-native + 不验证)</h3>
 *
 * <p>本配置面向「绝大多数读路径 Demo」:</p>
 * <ul>
 *   <li>{@code shareNativeConnection=true} — 走 Lettuce 共享 Channel,Demo 跑得最快;</li>
 *   <li>{@code validateConnection=false}    — 走默认路径,不会每次 PING(测试 PING 用 {@link L308PooledRedisConfig});</li>
 *   <li>{@code destroyMethod="destroy"}     — 让 Spring 触发
 *       {@code LettuceConnectionFactory#destroy},是观察驱逐阶段的入口。</li>
 * </ul>
 *
 * <h4>典型断点</h4>
 * <ol>
 *   <li>{@link LettuceConnectionFactory#afterPropertiesSet()} — 第一次构建 Lettuce {@code RedisClient}</li>
 *   <li>{@link LettuceConnectionFactory#getConnection()}      — 命中共享分支(返回 SharedConnection 代理)</li>
 *   <li>{@link LettuceConnectionFactory#destroy()}            — 关闭共享连接 + EventLoopGroup</li>
 * </ol>
 *
 * @author leilei
 * @since 2026-04-30
 */
@Configuration
public class L308RedisConfig {

    /**
     * 共享原生连接的 Factory。{@code afterPropertiesSet} 阶段不会立刻 connect,
     * 只构建 {@code RedisClient}(Lettuce 的"客户端外壳",还没有真正打开 Channel)。
     * 第一次 {@code getConnection()} 才触发 lazy {@code initConnection()}。
     */
    @Bean(destroyMethod = "destroy")
    public LettuceConnectionFactory lettuceConnectionFactory() {

        LettuceConnectionFactory factory = new LettuceConnectionFactory(redisStandaloneConfiguration());
        factory.setShareNativeConnection(true);
        factory.setValidateConnection(false);
        return factory;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    static RedisStandaloneConfiguration redisStandaloneConfiguration() {

        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(RedisConfigUtils.getHost(), RedisConfigUtils.getPort());
        config.setDatabase(RedisConfigUtils.getDatabase());

        String password = RedisConfigUtils.getPassword();
        if (password != null && !password.isEmpty()) {
            config.setPassword(RedisPassword.of(password));
        }
        return config;
    }
}
