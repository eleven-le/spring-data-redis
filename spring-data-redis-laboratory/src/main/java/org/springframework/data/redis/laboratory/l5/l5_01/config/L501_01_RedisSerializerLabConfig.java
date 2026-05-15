package org.springframework.data.redis.laboratory.l5.l5_01.config;

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
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * L5-01 序列化策略实验配置（非 Spring Boot）。
 * <p>
 * 这里"故意"提供三套 RedisTemplate，对应文档第 4 节"按数据类型选型"：
 * <ul>
 *   <li>{@link #stringRedisTemplate} —— key/value 全字符串。
 *       适合验证码、Token、计数器、状态值，redis-cli 一眼看穿。</li>
 *   <li>{@link #jsonRedisTemplate} —— key/hashKey 走 String，value/hashValue 走
 *       {@link GenericJackson2JsonRedisSerializer}。
 *       适合用户画像、订单摘要、活动卡片这类需要"可读 + 跨类型"的对象。</li>
 *   <li>{@link #jdkRedisTemplate} —— 全部走 {@link JdkSerializationRedisSerializer}。
 *       生产环境一般不推荐，这里专门留给"演示 JDK 序列化坑"的实验用。</li>
 * </ul>
 * 三套 Template 共享同一个 LettuceConnectionFactory，因为"换连接"和"换序列化策略"是两件事。
 */
@Configuration
public class L501_01_RedisSerializerLabConfig {

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

        LettuceConnectionFactory factory = new LettuceConnectionFactory(cfg);
        factory.setShareNativeConnection(true);
        return factory;
    }

    /**
     * 全字符串 Template。底层等价于 {@code RedisTemplate<String, String>} + 4 把 StringRedisSerializer。
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * 对象 Template：key/hashKey 走 String，value/hashValue 走 GenericJackson2JsonRedisSerializer。
     * <p>
     * {@link GenericJackson2JsonRedisSerializer} 会在 JSON 里写入 {@code "@class"} 元信息，
     * 反序列化时不依赖业务侧传 Class，是多态/集合/Object 场景的默认选择。
     * 但"类路径必须稳定"，跨服务共享缓存时要小心。
     */
    @Bean
    public RedisTemplate<String, Object> jsonRedisTemplate(LettuceConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(buildSafeMapper());

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        return template;
    }

    /**
     * 故意全走 JDK 序列化，专门给"JDK 序列化兼容陷阱"实验用。
     * <p>
     * 注意：用它写出来的 key 是 {@code \xAC\xED\x00\x05t\x00...} 这种二进制，
     * redis-cli KEYS 看到的是"乱码 key"，这是 JDK 序列化最容易被误用的地方。
     * 业务请勿照抄这个 Bean。
     */
    @Bean
    public RedisTemplate<Object, Object> jdkRedisTemplate(LettuceConnectionFactory factory) {
        RedisTemplate<Object, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        JdkSerializationRedisSerializer jdk = new JdkSerializationRedisSerializer();
        template.setKeySerializer(jdk);
        template.setValueSerializer(jdk);
        template.setHashKeySerializer(jdk);
        template.setHashValueSerializer(jdk);
        return template;
    }

    private static ObjectMapper buildSafeMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        return mapper;
    }
}
