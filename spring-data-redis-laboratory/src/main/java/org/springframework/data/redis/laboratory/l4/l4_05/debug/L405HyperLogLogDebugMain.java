package org.springframework.data.redis.laboratory.l4.l4_05.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_05.config.L405RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_05.hyperloglog.L405ActivityUvHyperLogLogScenario;
import org.springframework.data.redis.laboratory.l4.l4_05.hyperloglog.L405DailyUvHyperLogLogScenario;
import org.springframework.data.redis.laboratory.l4.l4_05.hyperloglog.L405HyperLogLogBasicOperationsLab;
import org.springframework.data.redis.laboratory.l4.l4_05.hyperloglog.L405SearchKeywordUvScenario;

import java.time.LocalDate;

/**
 * HyperLogLog 调试入口。
 * <p>
 * <b>建议断点位置</b>：
 * <ol>
 *   <li>{@code RedisTemplate#opsForHyperLogLog} —— 看子门面如何懒生成</li>
 *   <li>{@code DefaultHyperLogLogOperations#add} —— 看回调如何拼装</li>
 *   <li>{@code DefaultHyperLogLogOperations#size} —— 看序列化与多 key 处理</li>
 *   <li>{@code RedisTemplate#execute(RedisCallback)} —— 看模板方法</li>
 *   <li>{@code RedisConnectionUtils#doGetConnection} —— 看连接获取（事务感知）</li>
 *   <li>{@code LettuceConnection#hyperLogLogCommands} —— 看 Lettuce 桥接</li>
 * </ol>
 */
public class L405HyperLogLogDebugMain {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(L405RedisConfig.class)) {

            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);

            System.out.println("===== L4-05 HyperLogLog Debug =====");

            // ---------- 基础实验 ----------
            L405HyperLogLogBasicOperationsLab lab = new L405HyperLogLogBasicOperationsLab(template);
            String labKey = "l4:05:hll:lab:demo";
            template.delete(labKey);

            System.out.println("[lab] add → " + lab.add(labKey, "u-1", "u-2", "u-3", "u-1"));
            System.out.println("[lab] size → " + lab.size(labKey));
            lab.delete(labKey);

            // ---------- 场景 1：首页 UV ----------
            L405DailyUvHyperLogLogScenario uv = new L405DailyUvHyperLogLogScenario(template);
            uv.clearTodayUv();
            for (int i = 0; i < 1000; i++) {
                uv.recordHomeVisit("user-" + i);
            }
            // 模拟 100 个用户重复访问（HLL 自动去重）
            for (int i = 0; i < 100; i++) {
                uv.recordHomeVisit("user-" + i);
            }
            System.out.println("[home-uv] today (期望约 1000，HLL 有 ~0.81% 误差) → " + uv.getTodayUv());

            // ---------- 场景 2：活动 UV + PFMERGE ----------
            L405ActivityUvHyperLogLogScenario activityUv = new L405ActivityUvHyperLogLogScenario(template);
            String act = "spring-festival-2026";
            for (int day = 0; day < 3; day++) {
                LocalDate d = LocalDate.now().minusDays(day);
                activityUv.clearActivityUv(act, d);
                for (int u = 0; u < 500; u++) {
                    activityUv.recordVisit(act, "user-" + (u + day * 100), d);
                }
            }
            long merged = activityUv.mergeActivityUv(act, LocalDate.now().minusDays(2), LocalDate.now());
            System.out.println("[activity-uv] 3 天合并 UV (期望约 700) → " + merged);

            // ---------- 场景 3：搜索关键词 ----------
            L405SearchKeywordUvScenario kw = new L405SearchKeywordUvScenario(template);
            kw.clearKeywordUv("珍珠奶茶", LocalDate.now());
            kw.recordSearch("珍珠奶茶", "user-1");
            kw.recordSearch("珍珠奶茶", "user-2");
            kw.recordSearch("珍珠奶茶", "user-1"); // 去重
            System.out.println("[search-uv] 珍珠奶茶 today → " + kw.getKeywordUv("珍珠奶茶", LocalDate.now()));

            System.out.println("===== HyperLogLog Debug Done =====");
        }
    }
}
