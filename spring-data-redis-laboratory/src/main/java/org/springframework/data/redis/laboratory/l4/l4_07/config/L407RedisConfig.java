package org.springframework.data.redis.laboratory.l4.l4_07.config;

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
 * L4-07 共用 Spring 配置（非 Spring Boot）。
 * <p>
 * 暴露三类 Bean：
 * <ul>
 *   <li>{@link LettuceConnectionFactory} —— 底层连接工厂；Pipeline 调试时务必断点
 *       {@code LettuceConnection#openPipeline / closePipeline}。</li>
 *   <li>{@link StringRedisTemplate} —— 大多数场景（计数、删除、字符串补缓存）首选；
 *       redis-cli MGET/HGETALL 直接看得见，方便观察 Pipeline 写入是否真的落库。</li>
 *   <li>{@code RedisTemplate<String, Object>} —— value 是 POJO 的场景（商品详情、用户资料）使用，
 *       value 序列化器换成 Jackson，避免默认 JDK 序列化下乱码到 redis-cli 看不懂。</li>
 * </ul>
 * <p>
 * 为什么不用默认 JdkSerializationRedisSerializer： <p>
 * 1) Pipeline 一次返回一组 mixed results，反序列化结果会混 Long/Boolean/byte[]/List，
 *    用 JDK 序列化时 byte[] 在 redis-cli 看就是 \xAC\xED 等"乱码"，调试源码毫无用处；  <p>
 * 2) 业务接 Pipeline 后，C 端常见栈 (Web/Feign/MQ) 都需要 JSON 互通； <p>
 * 3) Jackson 序列化器开启默认类型信息，反序列化能拿回原 POJO，方便结果映射断点观察。 <p>
 * <p>
 * host/port 可通过 -Dredis.host / -Dredis.port 覆盖；默认走 redis.properties。
 */
@Configuration
public class L407RedisConfig {

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
        // Pipeline 必须用"非共享连接"，否则 openPipeline 会污染共享 native 连接，
        // 导致同一连接上其他线程的同步命令读到 pipeline 残留响应。
        // 这里关闭 shareNativeConnection，让每次 getConnection 拿独立 native 连接。
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
        GenericJackson2JsonRedisSerializer valSer = new GenericJackson2JsonRedisSerializer(buildSafeMapper());

        template.setKeySerializer(keySer);
        template.setHashKeySerializer(keySer);
        template.setValueSerializer(valSer);
        template.setHashValueSerializer(valSer);
        template.afterPropertiesSet();
        return template;
    }

    private static ObjectMapper buildSafeMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        return mapper;
    }
}
