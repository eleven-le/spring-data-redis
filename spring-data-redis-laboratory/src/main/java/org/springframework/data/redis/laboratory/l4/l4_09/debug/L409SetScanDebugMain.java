package org.springframework.data.redis.laboratory.l4.l4_09.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_09.config.L409RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_09.set.L409SetScanLab;

import java.util.List;

/**
 * SSCAN 调试入口。
 * <p>
 * <b>建议断点</b>：
 * <ul>
 *   <li>{@code SetOperations.scan(K, ScanOptions)}</li>
 *   <li>{@code DefaultSetOperations.scan}</li>
 *   <li>{@code RedisSetCommands.sScan} / {@code LettuceSetCommands.sScan}</li>
 *   <li>{@code Cursor<V>}</li>
 * </ul>
 */
public class L409SetScanDebugMain {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(L409RedisConfig.class)) {

            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);
            L409SetScanLab lab = new L409SetScanLab(template);
            String key = lab.defaultKey();

            System.out.println("===== L4-09 SSCAN Debug =====");
            lab.prepareLargeSet(key, 5_000);
            System.out.println("[prepare] set size=" + template.opsForSet().size(key));

            List<String> sample = lab.sscanAll(key, 200, 200);
            System.out.println("[sscan] sample.size=" + sample.size() + ", first5=" + sample.subList(0, Math.min(5, sample.size())));

            long processed = lab.sscanAndProcessInBatches(key, 200, 500, batch -> {
                // 模拟下游同步
            });
            System.out.println("[sscan in batches dedup] processed=" + processed);

            lab.cleanup(key);
            System.out.println("===== SSCAN Debug Done =====");
        }
    }
}
