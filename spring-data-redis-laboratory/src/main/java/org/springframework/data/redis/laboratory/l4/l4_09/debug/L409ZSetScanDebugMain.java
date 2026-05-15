package org.springframework.data.redis.laboratory.l4.l4_09.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.laboratory.l4.l4_09.config.L409RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_09.zset.L409ZSetScanLab;

import java.util.List;

/**
 * ZSCAN 调试入口。
 * <p>
 * <b>建议断点</b>：
 * <ul>
 *   <li>{@code ZSetOperations.scan(K, ScanOptions)}</li>
 *   <li>{@code DefaultZSetOperations.scan}</li>
 *   <li>{@code RedisZSetCommands.zScan} / {@code LettuceZSetCommands.zScan}</li>
 *   <li>{@code Cursor<TypedTuple<V>>}</li>
 * </ul>
 */
public class L409ZSetScanDebugMain {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(L409RedisConfig.class)) {
            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);
            L409ZSetScanLab lab = new L409ZSetScanLab(template);
            String key = lab.defaultKey();

            System.out.println("===== L4-09 ZSCAN Debug =====");
            lab.prepareLargeZSet(key, 3_000);
            System.out.println("[prepare] zset size=" + template.opsForZSet().size(key));

            List<ZSetOperations.TypedTuple<String>> sample = lab.zscanAll(key, 200, 100);
            System.out.println("[zscan] sample.size=" + sample.size());

            long processed = lab.zscanAndProcessInBatches(key, 200, 500, batch -> {
                // 模拟修复 / 检查
            });
            System.out.println("[zscan in batches] processed=" + processed);

            // 对照：业务展示走 ZREVRANGE
            System.out.println("[zrevrange top10] sample=" + lab.compareRangeVsZScanConcept(key, 10));

            lab.cleanup(key);
            System.out.println("===== ZSCAN Debug Done =====");
        }
    }
}
