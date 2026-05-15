package org.springframework.data.redis.laboratory.l4.l4_06.config;

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
 * Notion 章节：L4-06 Stream 消息队列 / 04 Spring Data Redis 对 Stream 的抽象 + 06.03 消息体设计
 *
 * 本配置类解决什么问题：
 *  1. 在非 Spring Boot 环境下，提供 Stream 实验所需的所有底层 Bean（连接工厂 + 两套模板）；
 *  2. 序列化器选 String key + Jackson value，方便 redis-cli 直接 XRANGE 看到 JSON 真容；
 *  3. 关闭 Lettuce 共享连接，避免后续 Pipeline / 阻塞 XREAD 实验互相串台。
 *
 * 关键 Spring Data Redis API：
 *  - {@link LettuceConnectionFactory}（驱动层）
 *  - {@link StringRedisTemplate}（Stream MapRecord 实验入口，redis-cli 友好）
 *  - {@link RedisTemplate}（Stream ObjectRecord / DLQ JSON 实验入口）
 *
 * 建议断点：
 *  - {@link LettuceConnectionFactory#getConnection()}（看 Lettuce 怎么换出 RedisConnection）
 *  - StringRedisTemplate 构造 / RedisTemplate#afterPropertiesSet（看模板的初始化时机）
 *
 * 新手避坑：
 *  - 一旦 value 用 JDK 默认序列化器，redis-cli XRANGE 看到的全是 \xAC\xED 乱码，调试源码毫无收益；
 *  - StreamMessageListenerContainer 的反序列化器要与生产端保持一致，否则消费侧解析失败；
 *  - shareNativeConnection 默认 true，跑 XREAD BLOCK 时极易污染共享连接，本类显式关掉。
 */
@Configuration
public class L406RedisConfig {

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
        // Stream 监听容器内部会反复获取连接做 XREADGROUP BLOCK 阻塞读，
        // 共享连接遇到阻塞读会让其他线程发出的命令"卡车队"。
        // 关掉共享连接，每次拿独立 native 连接，与 L4-07 Pipeline 实验保持一致。
        factory.setShareNativeConnection(false);
        return factory;
    }

    /**
     * StringRedisTemplate：
     *  - opsForStream() 默认拿到的是 StringRecord/MapRecord<String,String,String>；
     *  - redis-cli 直接 XRANGE 能看到字段，调试体验最好；
     *  - 本节大部分 demo 主推 StringRedisTemplate。
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * RedisTemplate&lt;String, Object&gt;：
     *  - 用于 ObjectRecord 实验、DLQ Payload 写 JSON、幂等结果存对象等场景；
     *  - hashKey/hashValue 用 Jackson，让 ObjectHashMapper 工作链路完整。
     */
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
        template.afterPropertiesSet();
        return template;
    }

    private static ObjectMapper buildSafeMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        return mapper;
    }
}
