package org.springframework.data.redis.laboratory.l4.l4_09.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_09.config.L409RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_09.scan.L409ScanBasicOperationsLab;

import java.time.Duration;
import java.util.List;

/**
 * Scan keyspace 基础调试入口。
 * <p>
 * <b>建议断点位置</b>：
 * <ol>
 *   <li>{@code RedisTemplate.scan(ScanOptions)} —— 顶层入口</li>
 *   <li>{@code RedisTemplate.executeWithStickyConnection(RedisCallback)} —— sticky 连接获取</li>
 *   <li>{@code RedisConnectionUtils.doGetConnection} —— 拿连接的钩子</li>
 *   <li>{@code LettuceConnection.scan(ScanOptions)} —— Lettuce 适配层</li>
 *   <li>{@code ScanCursor.scan(long)} —— 真正发出 SCAN 命令的方法</li>
 *   <li>{@code Cursor.hasNext / next} —— 看 cursor 内部如何攒一批</li>
 *   <li>{@code Cursor.close} —— 看 sticky 连接如何归还</li>
 *   <li>{@code ScanOptions.ScanOptionsBuilder.build()} —— 看参数对象怎么构造</li>
 * </ol>
 */
public class L409ScanDebugMain {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(L409RedisConfig.class)) {
            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);

            L409ScanBasicOperationsLab lab = new L409ScanBasicOperationsLab(template);
            System.out.println("===== L4-09 Scan Keyspace Debug =====");
            int n = 200;
            lab.prepareDemoData(n, Duration.ofMinutes(10));
            System.out.println("[prepare] 写入 " + n + " 个 demo key");

            // 1. 按 pattern 扫
            List<String> keys = lab.scanByPattern("l4:09:scan:demo:*", 100, 1000);
            System.out.println("[scan pattern] size=" + keys.size() + ", first5=" + keys.subList(0, Math.min(5, keys.size())));

            // 2. try-with-resources
            List<String> safe = lab.scanWithTryWithResources("l4:09:scan:demo:*", 50, 50);
            System.out.println("[try-with-resources] size=" + safe.size());

            // 3. 演示 count 是 hint
            System.out.println("[count is hint] roundSizes=" + lab.demonstrateCountIsHint("l4:09:scan:demo:*", 10, 5));

            // 4. 不是分页
            lab.demonstrateScanIsNotPagination();

            // 5. 清理
            lab.cleanupDemoData(n);
            System.out.println("===== Scan Keyspace Debug Done =====");
        }
    }
}
