package org.springframework.data.redis.laboratory.l4.l4_09.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_09.config.L409RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_09.hash.L409HashScanLab;

import java.util.List;
import java.util.Map;

/**
 * HSCAN 调试入口。
 * <p>
 * <b>建议断点</b>：
 * <ul>
 *   <li>{@code HashOperations.scan(K, ScanOptions)}</li>
 *   <li>{@code DefaultHashOperations.scan} —— 模板 execute 入口</li>
 *   <li>{@code RedisTemplate.executeWithStickyConnection}</li>
 *   <li>{@code RedisHashCommands.hScan} / {@code LettuceHashCommands.hScan}</li>
 *   <li>{@code Cursor<Map.Entry<HK,HV>>}</li>
 * </ul>
 */
public class L409HashScanDebugMain {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(L409RedisConfig.class)) {
            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);
            L409HashScanLab lab = new L409HashScanLab(template);
            String key = lab.defaultKey();

            System.out.println("===== L4-09 HSCAN Debug =====");
            lab.prepareLargeHash(key, 5_000);
            System.out.println("[prepare] hash size=" + template.opsForHash().size(key));

            // 1. 全量 HSCAN（限收 100）
            List<Map.Entry<String, String>> sample = lab.hscanAll(key, 200, 100);
            System.out.println("[hscan all] sample.size=" + sample.size());

            // 2. field pattern (view:*)
            List<Map.Entry<String, String>> views = lab.hscanByFieldPattern(key, "view:*", 200, 100);
            System.out.println("[hscan view:*] size=" + views.size());

            // 3. 分批处理
            long processed = lab.hscanAndProcessInBatches(key, 200, 500, batch -> {
                // 模拟下游处理
            });
            System.out.println("[hscan in batches] processed=" + processed);

            lab.cleanup(key);
            System.out.println("===== HSCAN Debug Done =====");
        }
    }
}
