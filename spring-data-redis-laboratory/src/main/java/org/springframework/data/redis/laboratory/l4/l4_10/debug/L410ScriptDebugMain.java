package org.springframework.data.redis.laboratory.l4.l4_10.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.laboratory.l4.l4_10.L410Keys;
import org.springframework.data.redis.laboratory.l4.l4_10.config.L410RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_10.result.IdempotentResult;
import org.springframework.data.redis.laboratory.l4.l4_10.scenario.L410IdempotentLuaScenario;
import org.springframework.data.redis.laboratory.l4.l4_10.script.L410RedisScriptBasicLab;

import java.time.Duration;
import java.util.Collections;

/**
 * RedisScript 基础调试入口(生产级)。
 * <p>
 * 断点路径见类内注释,所有业务脚本现在均返回 cjson JSON,Java 侧用 Jackson 解析为 POJO。
 */
public class L410ScriptDebugMain {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(L410RedisConfig.class)) {

            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);
            ObjectMapper mapper = ctx.getBean(ObjectMapper.class);
            @SuppressWarnings("unchecked")
            RedisScript<String> idem = (RedisScript<String>) ctx.getBean("idempotentMarkScript");

            L410RedisScriptBasicLab lab = new L410RedisScriptBasicLab(template);

            // ① 内联 INCRBY
            String labKey = L410Keys.labKey(1);
            template.delete(labKey);
            System.out.println("[01-inline] INCRBY=" + lab.executeInlineScript(labKey, 5));

            // ② classpath 等价路径(独立 INCR key)
            System.out.println("[02-classpath] INCR=" + lab.executeClasspathScript());

            // ③ 注入 RedisScript Bean,幂等门
            L410IdempotentLuaScenario idemScenario = new L410IdempotentLuaScenario(template, idem, mapper);
            idemScenario.clearMark("debug", "req-001");
            IdempotentResult first = idemScenario.tryEnter("debug", "req-001", Duration.ofSeconds(60),
                    () -> "{\"status\":\"PROCESSING\",\"createdAt\":" + System.currentTimeMillis() + "}");
            IdempotentResult dup = idemScenario.tryEnter("debug", "req-001", Duration.ofSeconds(60),
                    () -> "{\"status\":\"PROCESSING\"}");
            System.out.println("[03-bean] firstTime=" + first);
            System.out.println("[03-bean] replay   =" + dup);

            // ⑤ resultType
            lab.demonstrateResultType();

            // ⑥ argsSerializer
            lab.demonstrateArgsSerializer();

            // ⑦ EVALSHA fallback EVAL
            idemScenario.clearMark("debug", "req-002");
            String idemKey2 = L410Keys.idem("debug", "req-002");
            String now = String.valueOf(System.currentTimeMillis());
            lab.demonstrateEvalShaFallbackConcept(idem, Collections.singletonList(idemKey2),
                    "req-002", "60", now, "{\"k\":\"v\"}");

            // 清理
            idemScenario.clearMark("debug", "req-001");
            idemScenario.clearMark("debug", "req-002");
            template.delete(labKey);
            template.delete("l4:10:script:lab:cp");
        }
    }
}
