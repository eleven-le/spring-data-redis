package org.springframework.data.redis.laboratory.l3_06.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.util.RedisConfigUtils;

/**
 * <h3>L3-06 实验公共配置：把 LettuceConnection 装进 Spring 容器</h3>
 *
 * <p>本章主题是<b>「LettuceConnection 对 Lettuce API 的适配」</b>。在做任何调试之前，
 * 我们要先把 {@link LettuceConnectionFactory}、{@link StringRedisTemplate} 当成普通 Bean
 * 装进 {@link org.springframework.context.annotation.AnnotationConfigApplicationContext}，
 * 这样才能让 Spring 帮我们走完 {@code afterPropertiesSet}/{@code destroy} 的生命周期。</p>
 *
 * <p>这一份配置和 L3-05 的差别：</p>
 * <ul>
 * <li>L3-05 关注的是 shared / dedicated connection 的分流；</li>
 * <li>L3-06 关注的是<b>「适配层」</b>，即 RedisTemplate→RedisConnection→LettuceConnection→Lettuce
 *     原生 API 的层层穿越。</li>
 * </ul>
 *
 * <p>你不需要 Spring Boot：所有 Bean 都是显式声明，方便在 IDEA 里点进每一个对象看类型。</p>
 *
 * @author leilei
 * @since 2026-04-29
 */
@Configuration
public class L306RedisConfig {

    /**
     * <h4>LettuceConnectionFactory：Spring Data Redis 的「连接资源工厂」</h4>
     *
     * <p>它的角色和 {@code DataSource} 极度相似：</p>
     * <ul>
     * <li>{@link org.springframework.beans.factory.InitializingBean#afterPropertiesSet()}
     *     里完成 Lettuce {@code RedisClient} 的创建与共享连接的预热；</li>
     * <li>{@link org.springframework.beans.factory.DisposableBean#destroy()} 里把客户端、
     *     共享连接、连接池一次性关闭。</li>
     * </ul>
     *
     * <p>注意 {@code destroyMethod = "destroy"}：手动配置时一定要交给容器关闭，
     * 不然 Netty EventLoop 会留在 JVM 里跑。</p>
     *
     * @return 标准 LettuceConnectionFactory
     */
    @Bean(destroyMethod = "destroy")
    public LettuceConnectionFactory lettuceConnectionFactory() {

        LettuceConnectionFactory factory = new LettuceConnectionFactory(redisStandaloneConfiguration());
        factory.setShareNativeConnection(true);
        factory.setValidateConnection(false);
        return factory;
    }

    /**
     * <h4>StringRedisTemplate：业务上层唯一应该依赖的对象</h4>
     *
     * <p>它本质上是 {@link org.springframework.data.redis.core.RedisTemplate} 的字符串特化版本。
     * 所有操作都走 {@code execute(RedisCallback)}，业务层完全感知不到 LettuceConnection
     * 的存在 —— 这就是适配层的价值。</p>
     *
     * @param connectionFactory 父 Bean
     * @return StringRedisTemplate
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    private static RedisStandaloneConfiguration redisStandaloneConfiguration() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(RedisConfigUtils.getHost(), RedisConfigUtils.getPort());
        config.setDatabase(RedisConfigUtils.getDatabase());
        String password = RedisConfigUtils.getPassword();
        if (password != null && !password.isEmpty()) {
            config.setPassword(RedisPassword.of(password));
        }
        return config;
    }
}
