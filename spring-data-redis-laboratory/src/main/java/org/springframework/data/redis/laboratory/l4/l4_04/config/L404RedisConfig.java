package org.springframework.data.redis.laboratory.l4.l4_04.config;

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
 * L4-04 共用 Spring 配置（非 Spring Boot）。
 * <p>
 * 暴露两套 Template：
 * <ul>
 *   <li>{@link StringRedisTemplate} —— List/Set/ZSet 的 member 是字符串数字（订单号、商品 ID、用户 ID 等）时首选；
 *       redis-cli 直接 LRANGE / SMEMBERS / ZRANGE 可读。</li>
 *   <li>{@code RedisTemplate<String, Object>} —— 当 member/value 是 POJO（含序列化）时使用。</li>
 * </ul>
 * <p>
 * 之所以不用 RedisTemplate 默认的 JdkSerializationRedisSerializer：
 * 1) JDK 序列化的 key/member 是 \xAC\xED... 字节流，redis-cli SMEMBERS / ZRANGE 看不到人类可读字符串；
 * 2) 跨语言/跨服务读不出来；
 * 3) 反序列化历史上是 RCE 重灾区。
 * <p>
 * host/port 可被 -Dredis.host / -Dredis.port 系统属性覆盖。
 */
@Configuration
public class L404RedisConfig {

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
        return template;
    }

    private static ObjectMapper buildSafeMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        return mapper;
    }
}
