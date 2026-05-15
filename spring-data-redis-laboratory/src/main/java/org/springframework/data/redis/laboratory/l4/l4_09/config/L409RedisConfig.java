package org.springframework.data.redis.laboratory.l4.l4_09.config;

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
 * L4-09 Scan 游标遍历专用 Spring 配置（非 Spring Boot）。
 * <p>
 * 为什么 Scan 章要单独一份 RedisConfig 而不是复用 L4-07：
 * <ul>
 *   <li>Scan 是 <b>有状态命令</b>——cursor 在服务器端，每次 next 必须打回同一条连接，
 *       否则 cursor 立即失效。SDR 内部对 RedisTemplate.scan / Operations.scan 走的是
 *       executeWithStickyConnection，一次扫描期间 sticky 在同一条 native 连接上。</li>
 *   <li>因此 <b>shareNativeConnection=true</b> 在 Scan 这里反而是合理默认（默认就是 true，
 *       这里显式保留），让 Scan 更接近真实业务环境。</li>
 *   <li>Pipeline 章关闭了共享连接（避免污染）；Scan 不开 pipeline、不打 multi，所以没问题。</li>
 * </ul>
 * <p>
 * key/hashKey 一律 String 序列化，方便 redis-cli 直接读 l4:09:* 前缀做 sanity check；
 * value/hashValue 用 Jackson，活动元数据 / 用户画像这类 POJO 写入后能在 redis-cli 看出来。
 * 不要用 JdkSerializationRedisSerializer——Scan 出来的 key 在 redis-cli 看是
 * `\xAC\xED\x00\x05` 一坨乱码，断点 valueSerializer 反序列化时也不直观。
 * <p>
 * host/port 通过 -Dredis.host / -Dredis.port 覆盖；默认走 redis.properties。
 */
@Configuration
public class L409RedisConfig {

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
        // Scan 期间 SDR 走 executeWithStickyConnection 把 cursor 绑死在一条连接上，
        // 共享 native 连接是默认值且无害；这里显式置 true 让代码自解释。
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
