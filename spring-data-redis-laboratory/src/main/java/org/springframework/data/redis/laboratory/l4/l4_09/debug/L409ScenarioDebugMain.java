package org.springframework.data.redis.laboratory.l4.l4_09.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_09.config.L409RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_09.scenario.L409ActivityCacheCleanScenario;
import org.springframework.data.redis.laboratory.l4.l4_09.scenario.L409BatchCleanCacheScenario;
import org.springframework.data.redis.laboratory.l4.l4_09.scenario.L409CacheVersionMigrationScenario;
import org.springframework.data.redis.laboratory.l4.l4_09.scenario.L409LargeHashIncrementalProcessScenario;
import org.springframework.data.redis.laboratory.l4.l4_09.scenario.L409LargeSetIncrementalSyncScenario;
import org.springframework.data.redis.laboratory.l4.l4_09.scenario.L409LargeZSetCheckScenario;
import org.springframework.data.redis.laboratory.l4.l4_09.scenario.L409ScanPipelineBatchProcessScenario;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * 真实业务场景一站式调试入口——一次跑完 7 个 Scenario。
 * <p>
 * 走完一遍能完整看到：
 * <ul>
 *   <li>Scan + UNLINK 批量清理</li>
 *   <li>活动缓存 dry-run + 真实清理</li>
 *   <li>v1 → v2 版本迁移</li>
 *   <li>大 Hash HSCAN 增量处理</li>
 *   <li>大 Set SSCAN 增量同步</li>
 *   <li>大 ZSet ZSCAN 检查</li>
 *   <li>Scan + Pipeline 组合</li>
 * </ul>
 */
public class L409ScenarioDebugMain {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(L409RedisConfig.class)) {

            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);

            System.out.println("===== L4-09 Scenario Debug =====");

            // 1. 批量清理
            L409BatchCleanCacheScenario clean = new L409BatchCleanCacheScenario(template);
            clean.prepareDemoData(300, Duration.ofMinutes(30));

            System.out.println("[1.dry-run] sample=" + clean.dryRunClean("l4:09:clean:product:detail:old:*", 200, 5));
            long unlinked = clean.cleanByScanAndUnlinkIfSupported("l4:09:clean:product:detail:old:*", 200, 100, 0L);
            System.out.println("[1.clean] unlinked=" + unlinked);

            // 2. 活动缓存清理
            L409ActivityCacheCleanScenario activity = new L409ActivityCacheCleanScenario(template);
            String aid = "618-debug";
            activity.prepareActivityCache(aid, 50, 80, Duration.ofMinutes(30));
            System.out.println("[2.activity dry-run] sample.size=" + activity.dryRunActivityCache(aid, 200, 10).size());
            long cleared = activity.cleanActivityCache(aid, 200, 100, 0L);
            System.out.println("[2.activity clean] cleared=" + cleared);

            // 3. 版本迁移
            L409CacheVersionMigrationScenario migrate = new L409CacheVersionMigrationScenario(template);
            List<String> users = Arrays.asList("u1", "u2", "u3", "u4", "u5");
            migrate.prepareV1Profiles(users, Duration.ofMinutes(30));
            L409CacheVersionMigrationScenario.MigrateReport report = migrate.migrateV1ToV2(200, 50, Duration.ofMinutes(30));
            System.out.println("[3.migrate] " + report);
            for (String u : users) {
                System.out.println("[3.verify " + u + "] " + migrate.verifyMigration(u));
            }
            migrate.clearMigrationData(users);

            // 4. 大 Hash
            L409LargeHashIncrementalProcessScenario hash = new L409LargeHashIncrementalProcessScenario(template);
            hash.prepareUserBehavior("u100", 3_000);
            long h = hash.processBehaviorByHScan("u100", 200, 500);
            System.out.println("[4.large hash] processed=" + h);
            hash.cleanup("u100");

            // 5. 大 Set
            L409LargeSetIncrementalSyncScenario set = new L409LargeSetIncrementalSyncScenario(template);
            set.prepareLikeUsers("item-1", 3_000);
            long s = set.syncLikeUsersBySScan("item-1", 200, 500);
            System.out.println("[5.large set] synced=" + s);
            set.cleanup("item-1");

            // 6. 大 ZSet
            L409LargeZSetCheckScenario zset = new L409LargeZSetCheckScenario(template);
            zset.prepareRankData("20260503", 3_000);
            long dirty = zset.checkRankDataByZScan("20260503", 200, 500);
            System.out.println("[6.large zset] dirty=" + dirty);
            System.out.println("[6.zset top10] sample=" + zset.getTopNByRange("20260503", 10));
            zset.cleanup("20260503");

            // 7. Scan + Pipeline
            L409ScanPipelineBatchProcessScenario pipe = new L409ScanPipelineBatchProcessScenario(template);
            pipe.prepareDemoData(300);
            long expired = pipe.scanAndExpire("l4:09:pipeline:batch:*", Duration.ofMinutes(5), 200, 100);
            System.out.println("[7.scan+pipeline expire] count=" + expired);
            long deleted = pipe.scanAndDelete("l4:09:pipeline:batch:*", 200, 100);
            System.out.println("[7.scan+pipeline delete] count=" + deleted);

            System.out.println("===== Scenario Debug Done =====");
        }
    }
}
