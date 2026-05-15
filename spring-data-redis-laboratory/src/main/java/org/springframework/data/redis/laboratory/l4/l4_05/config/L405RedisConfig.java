package org.springframework.data.redis.laboratory.l4.l4_05.config;

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
 * L4-05 共用 Spring 配置（非 Spring Boot）。
 * <p>
 * 暴露三套 Bean：
 * <ul>
 *   <li>{@link LettuceConnectionFactory} —— 底层连接工厂，断点入口之一。</li>
 *   <li>{@link StringRedisTemplate} —— HyperLogLog / Bitmap / GEO 三种结构都使用人类可读字符串作为 key/member，
 *       默认 String 序列化器最贴合本章场景；redis-cli PFCOUNT / GETBIT / GEOPOS 都可读。</li>
 *   <li>{@code RedisTemplate<String, Object>} —— 当 value 是 POJO 时使用，本章主要给 toushi demo 留口子。</li>
 * </ul>
 * <p>
 * 不使用 RedisTemplate 默认 JdkSerializationRedisSerializer 的原因：
 * 1) Bitmap 的 key 直接落在 Redis String 上，乱码 key 用 redis-cli 几乎无法排查；
 * 2) GEO 的 member 想直接 GEOPOS 读出来，必须可读字符串；
 * 3) HLL 不存元素明细，但 key 本身仍要可读。
 * <p>
 * host/port 可被 -Dredis.host / -Dredis.port 覆盖。
 */
@Configuration
public class L405RedisConfig {

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
