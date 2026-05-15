package org.springframework.data.redis.laboratory.l4.l4_10.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.laboratory.util.RedisConfigUtils;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * L4-10 共用 Spring 配置(非 Spring Boot)。
 * <p>
 * 生产级返回值约定:所有业务脚本返回 cjson.encode 的 JSON 字符串,Java 侧用 Jackson 解析为强类型 POJO。
 * 这样脚本演化不会冲击 Java 接口签名,新增字段也是兼容的。
 * <p>
 * 因此 7 个脚本 Bean 全部 {@code RedisScript<String>},Lettuce 在 ScriptOutputType.VALUE 下读 BulkString。
 */
@Configuration
public class L410RedisConfig {

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
        return new LettuceConnectionFactory(cfg);
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

        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        GenericJackson2JsonRedisSerializer valSer = new GenericJackson2JsonRedisSerializer(mapper);

        template.setKeySerializer(keySer);
        template.setHashKeySerializer(keySer);
        template.setValueSerializer(valSer);
        template.setHashValueSerializer(valSer);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 全局 Jackson:解析脚本返回的 JSON。允许未知字段,便于脚本升级。
     */
    @Bean
    public ObjectMapper l410ObjectMapper() {
        ObjectMapper m = new ObjectMapper();
        m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return m;
    }

    // ============== 7 个 Lua 脚本 Bean(全部返回 JSON 字符串) ==============

    @Bean
    public RedisScript<String> stockDeductScript() {
        return loadString("redis/lua/l4_10/stock_deduct.lua");
    }

    @Bean
    public RedisScript<String> couponClaimScript() {
        return loadString("redis/lua/l4_10/coupon_claim.lua");
    }

    @Bean
    public RedisScript<String> idempotentMarkScript() {
        return loadString("redis/lua/l4_10/idempotent_mark.lua");
    }

    @Bean
    public RedisScript<String> lockReleaseScript() {
        return loadString("redis/lua/l4_10/lock_release.lua");
    }

    @Bean
    public RedisScript<String> slidingWindowRateLimitScript() {
        return loadString("redis/lua/l4_10/sliding_window_rate_limit.lua");
    }

    @Bean
    public RedisScript<String> delayQueueClaimScript() {
        return loadString("redis/lua/l4_10/delay_queue_claim.lua");
    }

    @Bean
    public RedisScript<String> counterThresholdScript() {
        return loadString("redis/lua/l4_10/counter_threshold.lua");
    }

    private static RedisScript<String> loadString(String classpath) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(classpath));
        // ScriptOutputType.VALUE -> BulkString -> Java String
        // 设成 Long 会在解析期 ClassCastException
        script.setResultType(String.class);
        return script;
    }
}
