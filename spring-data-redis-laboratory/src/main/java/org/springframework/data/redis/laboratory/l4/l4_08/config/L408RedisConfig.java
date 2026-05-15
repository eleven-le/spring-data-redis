package org.springframework.data.redis.laboratory.l4.l4_08.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.util.RedisConfigUtils;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * L4-08 共用 Spring 配置（非 Spring Boot）。
 * <p>
 * 提供：
 * <ul>
 *   <li>{@link LettuceConnectionFactory}：Lettuce 实现，事务调试时务必断点
 *       {@code LettuceConnection#multi/exec/discard/watch/unwatch}。</li>
 *   <li>{@link StringRedisTemplate}：大多数事务场景使用 String 序列化器，
 *       redis-cli HGET / GET 直接可见，便于断点反复观察事务结果。</li>
 *   <li>{@code RedisTemplate<String, Object>}：演示 mixed results 反序列化对结果类型的影响。</li>
 * </ul>
 * <p>
 * <b>为什么不用默认 JdkSerializationRedisSerializer：</b>
 * EXEC 一次返回 mixed results（Long / Boolean / byte[] / List），用 JDK 序列化时
 * 在 redis-cli 看就是 \xAC\xED 等"乱码"，调试事务源码毫无用处；C 端栈也都需要 JSON 互通。
 * <p>
 * <b>为什么默认 NOT 启用 setEnableTransactionSupport(true)：</b>
 * 这个开关的真实意图是让 Redis 操作纳入 Spring 声明式事务（PlatformTransactionManager）
 * 的同步周期；本章主线是显式 SessionCallback，开启会让新手误以为"普通命令也自动 Redis 事务"。
 * 单独的 {@code L408TransactionSupportLab} 才会临时打开。
 * <p>
 * host/port 可通过 -Dredis.host / -Dredis.port 覆盖；默认走 redis.properties。
 */
@Configuration
public class L408RedisConfig {

    @Bean(destroyMethod = "destroy")
    public LettuceConnectionFactory lettuceConnectionFactory() {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration();
        cfg.setHostName(System.getProperty("redis.host", RedisConfigUtils.getHost()));
        cfg.setPort(Integer.parseInt(System.getProperty("redis.port",
                String.valueOf(RedisConfigUtils.getPort()))));
        cfg.setDatabase(RedisConfigUtils.getDatabase());

        String pwd = RedisConfigUtils.getPassword();
        if (pwd != null && !pwd.isEmpty()) {
            cfg.setPassword(RedisPassword.of(pwd));
        }

        LettuceConnectionFactory factory = new LettuceConnectionFactory(cfg);
        // 事务必须使用"独占连接"：multi/exec/watch 都是连接级状态，
        // 共享 native connection 会让事务命令与异步命令在同一 connection 上穿插。
        // shareNativeConnection=false 后每次 getConnection 都拿独立 native 连接。
        factory.setShareNativeConnection(false);
        return factory;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(LettuceConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        StringRedisSerializer keySer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valSer =
                new GenericJackson2JsonRedisSerializer(buildSafeMapper());

        template.setKeySerializer(keySer);
        template.setHashKeySerializer(keySer);
        template.setValueSerializer(valSer);
        template.setHashValueSerializer(valSer);

        // 默认不开启事务同步支持（详见类 javadoc）。
        template.setEnableTransactionSupport(false);
        template.afterPropertiesSet();
        return template;
    }

    private static ObjectMapper buildSafeMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        return mapper;
    }
}
