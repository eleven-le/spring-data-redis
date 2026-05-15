package org.springframework.data.redis.laboratory.l4.l4_03.config;

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
 * L4-03 共用 Spring 配置（非 Spring Boot）。
 * <p>
 * 同时暴露两套 Template，对应教学中两条主线：
 * <ul>
 *   <li>{@link StringRedisTemplate} —— key/value/hashKey/hashValue 全部 UTF-8 字符串，
 *       适合验证码、计数器、幂等标记这种纯字符串/数字场景。redis-cli 直接可读。</li>
 *   <li>{@code RedisTemplate<String, Object>} —— key/hashKey 走 String 序列化，value/hashValue
 *       走 GenericJackson2JsonRedisSerializer，适合用户档案、购物车这种结构化对象。</li>
 * </ul>
 * <p>
 * 不使用 RedisTemplate 默认的 JdkSerializationRedisSerializer，因为：
 * 1) 默认序列化器把 String key 写成 "\xAC\xED\x00\x05..." 这种 JDK 序列化字节流，redis-cli KEYS 看不到人类可读的 key；
 * 2) JDK 序列化版本敏感，跨语言/跨服务读不出来；
 * 3) 反序列化历史上是 RCE 重灾区（gadget chain）。
 * <p>
 * host/port 通过 {@link RedisConfigUtils} 读取 redis.properties，可被系统属性 redis.host/redis.port 覆盖。
 */
@Configuration
public class L403RedisConfig {

    @Bean(destroyMethod = "destroy")
    public LettuceConnectionFactory lettuceConnectionFactory() {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration();
        // 允许通过 -Dredis.host / -Dredis.port 覆盖（命令行 / IDE Run Configuration）
        cfg.setHostName(System.getProperty("redis.host", RedisConfigUtils.getHost()));
        cfg.setPort(Integer.parseInt(System.getProperty("redis.port", String.valueOf(RedisConfigUtils.getPort()))));
        cfg.setDatabase(RedisConfigUtils.getDatabase());

        String pwd = RedisConfigUtils.getPassword();
        if (pwd != null && !pwd.isEmpty()) {
            cfg.setPassword(RedisPassword.of(pwd));
        }

        LettuceConnectionFactory factory = new LettuceConnectionFactory(cfg);
        factory.setShareNativeConnection(true);
        return factory;
    }

    /**
     * 纯字符串 Template。底层是 RedisTemplate<String, String> + StringRedisSerializer ×4。
     * 断点学习推荐入口：{@code StringRedisTemplate#opsForValue}、
     * {@code DefaultValueOperations#set} → {@code RedisTemplate#execute}。
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * 对象 Template。
     * key/hashKey: StringRedisSerializer —— redis-cli 可读、便于运维和断点观测。
     * value/hashValue: GenericJackson2JsonRedisSerializer —— 自带 @class 元信息便于反序列化。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(LettuceConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer(buildSafeMapper());

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        // afterPropertiesSet 会被 Spring 容器在 bean init 阶段触发；这里不必手动调
        return template;
    }

    /**
     * 让 Jackson 不强制 POJO 写 getter/setter（实验场景使用，生产请按需收紧可见性）。
     */
    private static ObjectMapper buildSafeMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        return mapper;
    }
}
