package org.springframework.data.redis.laboratory.l5.l5_02.config;

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
import org.springframework.data.redis.laboratory.l5.l5_02.jackson.L502_03_JacksonSerializerChoiceScenario;
import org.springframework.data.redis.laboratory.util.RedisConfigUtils;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * L5-02 序列化家族实验配置（非 Spring Boot）。
 * <p>
 * 这里"故意"提供 5 套 RedisTemplate 作为对照组，对应文档 §1 家族总览 / §2 选型总表：
 * <ul>
 *   <li>{@link #stringRedisTemplate} —— Token / 验证码 / 状态值的主战场。</li>
 *   <li>{@link #genericJsonRedisTemplate} —— 单服务内部多态/复杂对象的默认。</li>
 *   <li>{@link #typedJacksonRedisTemplate} —— 跨服务共享稳定 DTO 的推荐。</li>
 *   <li>{@link #jdkRedisTemplate} —— 专门用于演示 JDK 序列化坑，业务请勿照抄。</li>
 *   <li>{@link #byteArrayRedisTemplate} —— byte[] 透传场景（上游已 Protobuf/Kryo 编码完）。</li>
 * </ul>
 * 5 套 Template 共享同一个 LettuceConnectionFactory：连接和序列化策略是两件事，
 * 这也是 Spring Data Redis 把"连接管理"和"协议翻译"完全解耦的设计。
 */
@Configuration
public class L502_01_SerializerFamilyLabConfig {

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
     * 全字符串 Template。底层等价于 RedisTemplate&lt;String,String&gt; + 4 把 StringRedisSerializer。
     * <p>
     * 适用：登录 Token、验证码、状态值、分布式锁 value、计数器、活动开关。
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * 通用对象 Template：key/hashKey 走 String，value/hashValue 走 GenericJackson2JsonRedisSerializer。
     * <p>
     * 适用：单服务内部复杂对象缓存（用户画像、订单摘要、多态卡片列表）。
     * 危险：{@code @class} 元信息硬绑 Java 类路径——跨服务共享、包名重构都会触雷，详见文档 §4。
     */
    @Bean
    public RedisTemplate<String, Object> genericJsonRedisTemplate(LettuceConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(buildSafeMapper());

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 强类型 DTO Template：value/hashValue 走 Jackson2JsonRedisSerializer&lt;ProductDetailCacheDTO&gt;。
     * <p>
     * 适用：跨服务共享缓存、跨语言共享缓存、稳定 DTO 协议。
     * JSON 干净不带 {@code @class}，下游（含 Go/Node 服务）可直接读字段名。
     * 局限：构造时绑死目标类型——多态/泛型集合场景需要泛型擦除处理，详见文档 §4.2 与 [[L5-04]]。
     */
    @Bean
    public RedisTemplate<String, L502_03_JacksonSerializerChoiceScenario.ProductDetailCacheDTO>
            typedJacksonRedisTemplate(LettuceConnectionFactory factory) {
        RedisTemplate<String, L502_03_JacksonSerializerChoiceScenario.ProductDetailCacheDTO> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        Jackson2JsonRedisSerializer<L502_03_JacksonSerializerChoiceScenario.ProductDetailCacheDTO> typed =
                new Jackson2JsonRedisSerializer<>(L502_03_JacksonSerializerChoiceScenario.ProductDetailCacheDTO.class);
        typed.setObjectMapper(buildSafeMapper());

        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(typed);
        template.setHashValueSerializer(typed);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 故意全走 JDK 序列化，给"演示 JDK 序列化坑"专用。
     * <p>
     * 注意：用它写出来的 key 是 {@code \xAC\xED\x00\x05t\x00...} 二进制——
     * 这就是为什么"不显式配序列化器"几乎注定踩坑的源头。
     * 生产业务请勿照抄。
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
        template.afterPropertiesSet();
        return template;
    }

    /**
     * byte[] 透传 Template：key 走 String，value 直接走 {@link RedisSerializer#byteArray()} 透传。
     * <p>
     * 适用：调用方已经握着 Protobuf/Kryo/压缩字节，让它原样进 Redis。
     * {@code RedisSerializer.byteArray()} 返回的就是包私有 {@code ByteArrayRedisSerializer.INSTANCE} 单例。
     */
    @Bean
    public RedisTemplate<String, byte[]> byteArrayRedisTemplate(LettuceConnectionFactory factory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(RedisSerializer.byteArray());
        template.setHashValueSerializer(RedisSerializer.byteArray());
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 实验用 ObjectMapper：字段全可见，避免 getter 缺失导致空 JSON。
     * <p>
     * 本章 DTO 故意只用 long 时间戳 + 基本类型——
     * 跨语言/跨服务共享缓存里 LocalDateTime 是 JavaTimeModule 强依赖，统一 epoch 毫秒更轻量。
     * 生产里你需要的还有 NULL 处理、BigDecimal 精度、命名策略等，按团队规范再加。
     */
    private static ObjectMapper buildSafeMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        return mapper;
    }
}
