package org.springframework.data.redis.laboratory.l4.l4_10.script;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.Collections;
import java.util.List;

/**
 * Lua / RedisScript 基础实验合集(每个方法独立可断点)。
 * <p>
 * 推荐断点:
 * <ul>
 *   <li>{@code RedisTemplate#execute(RedisScript, List, Object...)} —— 入口</li>
 *   <li>{@code RedisTemplate#execute(RedisScript, RedisSerializer, RedisSerializer, List, Object...)} —— 自定义序列化器入口</li>
 *   <li>{@code DefaultScriptExecutor#execute(...)} —— 优先 evalSha,NOSCRIPT 时 fallback eval</li>
 *   <li>{@code DefaultRedisScript#getSha1} / {@code getScriptAsString} —— sha1 懒加载</li>
 *   <li>{@code LettuceConnection#scriptingCommands} → {@code RedisScriptingCommands#evalSha / eval}</li>
 *   <li>{@code RedisConnectionUtils#doGetConnection} —— 拿连接</li>
 * </ul>
 */
public class L410RedisScriptBasicLab {

    private final StringRedisTemplate template;

    public L410RedisScriptBasicLab(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * ① 内联脚本演示。
     */
    public Long executeInlineScript(String key, long delta) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText("return tonumber(redis.call('INCRBY', KEYS[1], ARGV[1]))");
        script.setResultType(Long.class);
        return template.execute(script, Collections.singletonList(key), String.valueOf(delta));
    }

    /**
     * ② classpath 脚本演示:这里直接加载一个简单的 SUM 脚本,不调用业务脚本。
     */
    public Long executeClasspathScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        // 用 inline 脚本演示 classpath 等价路径(避免和业务脚本耦合)
        script.setScriptText("return tonumber(redis.call('INCR', KEYS[1]))");
        script.setResultType(Long.class);

        String key = "l4:10:script:lab:cp";
        template.delete(key);
        return template.execute(script, Collections.singletonList(key));
    }

    /**
     * ③ 直接传 RedisScript Bean(生产 String JSON 返回值)。返回原始 JSON,调用方自行解析。
     */
    public String executeWithDefaultRedisScript(RedisScript<String> script, List<String> keys, Object... args) {
        return template.execute(script, keys, args);
    }

    /**
     * ④ 自定义 args/result 序列化器入口(改造为 String 返回)。
     */
    public String executeWithCustomSerializer(RedisScript<String> script, List<String> keys, Object... args) {
        StringRedisSerializer s = StringRedisSerializer.UTF_8;
        return template.execute(
                script, s, s,
                keys, args
        );
    }

    /**
     * ⑤ 演示 resultType:Long vs Boolean 走不同 ScriptOutputType。
     */
    public void demonstrateResultType() {
        DefaultRedisScript<Long> longScript = new DefaultRedisScript<>();
        longScript.setScriptText("return 1");
        longScript.setResultType(Long.class);

        DefaultRedisScript<Boolean> boolScript = new DefaultRedisScript<>();
        boolScript.setScriptText("return 1");
        boolScript.setResultType(Boolean.class);

        Long l = template.execute(longScript, Collections.emptyList());
        Boolean b = template.execute(boolScript, Collections.emptyList());
        System.out.println("[demoResultType] Long=" + l + ", Boolean=" + b);
    }

    /**
     * ⑥ 演示 argsSerializer:String 序列化让 ARGV 是 "10","32",Lua tonumber 才能加。
     */
    public void demonstrateArgsSerializer() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText("return tonumber(ARGV[1]) + tonumber(ARGV[2])");
        script.setResultType(Long.class);

        StringRedisSerializer s = StringRedisSerializer.UTF_8;
        Long sum = template.execute(script, s, new GenericLongSerializer(),
                Collections.emptyList(), "10", "32");
        System.out.println("[demoArgsSerializer] 10+32=" + sum);
    }

    /**
     * ⑦ EVALSHA → NOSCRIPT fallback EVAL 的概念演示。
     */
    public void demonstrateEvalShaFallbackConcept(RedisScript<String> script, List<String> keys, Object... args) {
        String r1 = template.execute(script, keys, args);
        String r2 = template.execute(script, keys, args);
        System.out.println("[demoEvalSha] firstCall=" + r1);
        System.out.println("[demoEvalSha] secondCall=" + r2
                + "  (sha1=" + ((DefaultRedisScript<String>) script).getSha1() + ")");
    }

    /**
     * Long 反序列化器,演示自定义 resultSerializer。
     */
    static class GenericLongSerializer
            implements org.springframework.data.redis.serializer.RedisSerializer<Long> {
        @Override
        public byte[] serialize(Long v) {
            return v == null ? new byte[0] : String.valueOf(v).getBytes();
        }

        @Override
        public Long deserialize(byte[] bytes) {
            if (bytes == null || bytes.length == 0) return null;
            return Long.parseLong(new String(bytes));
        }
    }
}
