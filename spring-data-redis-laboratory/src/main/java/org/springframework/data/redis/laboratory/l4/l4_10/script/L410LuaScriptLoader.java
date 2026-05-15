package org.springframework.data.redis.laboratory.l4.l4_10.script;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 把 classpath 下的 Lua 脚本加载为 RedisScript Bean 的工具。
 * <p>
 * 为什么把脚本文件化、并且封装成 Bean？
 * <ol>
 *   <li>脚本是稳定资源，不要在 Java 里字符串拼接：拼接 = 失去 sha1 缓存价值，
 *       而且容易把动态参数注入脚本（Lua 注入风险）。</li>
 *   <li>DefaultRedisScript 内部缓存 sha1，DefaultScriptExecutor 优先 EVALSHA，
 *       服务端 NOSCRIPT 时再 fallback EVAL，全靠它的稳定性。</li>
 *   <li>Bean 化以后业务方法只关心 KEYS/ARGV，不关心脚本生命周期。</li>
 * </ol>
 * <p>
 * 注意：这只是个手工加载入口，本章默认在 {@code L410RedisConfig} 里直接 @Bean 注册脚本，
 * 这里保留是为了让你看清"location -> sha1 -> resultType"三件事如何被 DefaultRedisScript 收口。
 */
public final class L410LuaScriptLoader {

    private L410LuaScriptLoader() {
    }

    public static RedisScript<Long> loadLong(String classpathLocation) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(classpathLocation));
        script.setResultType(Long.class);
        return script;
    }

    public static RedisScript<Boolean> loadBoolean(String classpathLocation) {
        DefaultRedisScript<Boolean> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(classpathLocation));
        script.setResultType(Boolean.class);
        return script;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static RedisScript<java.util.List> loadList(String classpathLocation) {
        DefaultRedisScript script = new DefaultRedisScript();
        script.setLocation(new ClassPathResource(classpathLocation));
        script.setResultType(java.util.List.class);
        return (RedisScript<java.util.List>) script;
    }

    public static <T> RedisScript<T> load(String classpathLocation, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(classpathLocation));
        script.setResultType(resultType);
        return script;
    }
}
