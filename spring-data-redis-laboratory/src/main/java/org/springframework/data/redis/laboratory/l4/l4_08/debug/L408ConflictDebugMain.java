package org.springframework.data.redis.laboratory.l4.l4_08.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.config.L408RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_08.scenario.L408WatchConflictSimulationScenario;

/**
 * WATCH 冲突复现主入口。
 * <p>
 * <b>建议断点</b>：
 * <ul>
 *   <li>{@code LettuceConnection#watch}</li>
 *   <li>{@code LettuceConnection#exec}（看返回 nil 的路径）</li>
 *   <li>{@code RedisTemplate#deserializeMixedResults}（看 null/empty 如何被业务拿到）</li>
 * </ul>
 */
public class L408ConflictDebugMain {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(L408RedisConfig.class)) {

            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);
            LettuceConnectionFactory factory = ctx.getBean(LettuceConnectionFactory.class);

            L408WatchConflictSimulationScenario sim =
                    new L408WatchConflictSimulationScenario(template, factory);

            String key = L408WatchConflictSimulationScenario.defaultKey();

            System.out.println("===== L4-08 Conflict Debug =====");
            System.out.println("[conflict] simulating two clients...");
            var result = sim.simulateTwoClientsConflict(key);
            System.out.println("[conflict] EXEC result = " + result
                    + " (期望 null/empty，表示冲突)");
            sim.explainConflictResult();

            sim.clear(key);
            System.out.println("===== Conflict Debug Done =====");
        }
    }
}
