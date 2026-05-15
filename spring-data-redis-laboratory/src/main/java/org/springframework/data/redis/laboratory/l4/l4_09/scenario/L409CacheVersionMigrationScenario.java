package org.springframework.data.redis.laboratory.l4.l4_09.scenario;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_09.L409Keys;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 场景 3：缓存版本迁移 v1 → v2。
 * <p>
 * <b>业务故事</b>：用户画像 schema 变更，旧版 {@code l4:09:migrate:v1:user:profile:{uid}} 是 plain string，
 * 新版 {@code l4:09:migrate:v2:user:profile:{uid}} 改成结构化 JSON 增加字段。需要在线迁移。
 * <p>
 * <b>Scan 的角色</b>：只负责"找到 v1 key"。一致性要业务层兜：
 * <ol>
 *   <li>双写期：业务写 v1 同时写 v2；</li>
 *   <li>双读期：先读 v2，miss 回源读 v1；</li>
 *   <li>迁移任务：Scan v1 + 转换 + 写 v2（设 TTL，幂等）；</li>
 *   <li>切流：流量灰度切到只读 v2；</li>
 *   <li>下线：观察一周后回收 v1。</li>
 * </ol>
 * <p>
 * Scan 找 key、读 v1、写 v2、设 TTL、记录失败、可重跑——本类专注这条链路。
 * 真正的双写双读 / 灰度切流不在本类——它们在业务网关层。
 */
public class L409CacheVersionMigrationScenario {

    private final StringRedisTemplate template;

    public L409CacheVersionMigrationScenario(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * 准备 v1 数据用于迁移演示。
     */
    public void prepareV1Profiles(List<String> userIds, Duration ttl) {
        for (String uid : userIds) {
            template.opsForValue().set(L409Keys.migrateV1Key(uid), "v1-profile-" + uid, ttl);
        }
    }

    /**
     * 迁移结果。
     */
    public static class MigrateReport {
        public long scanned;
        public long migrated;
        public long skipped;
        public long failed;
        public final List<String> failedKeys = new ArrayList<>();

        @Override
        public String toString() {
            return "MigrateReport{scanned=" + scanned + ", migrated=" + migrated
                    + ", skipped=" + skipped + ", failed=" + failed
                    + ", failedKeys=" + failedKeys + "}";
        }
    }

    /**
     * 主流程：Scan v1 → 转换 → 写 v2 → 记录失败。
     * 整个任务幂等：再跑一次只会"已存在 v2 则跳过"。
     */
    public MigrateReport migrateV1ToV2(int scanCount, int batchSize, Duration v2Ttl) {
        ScanOptions options = ScanOptions.scanOptions()
                .match(L409Keys.MIGRATE_V1_PREFIX + "*")
                .count(scanCount)
                .build();
        MigrateReport report = new MigrateReport();
        List<String> buffer = new ArrayList<>(batchSize);
        try (Cursor<String> cursor = template.scan(options)) {
            while (cursor.hasNext()) {
                buffer.add(cursor.next());
                if (buffer.size() >= batchSize) {
                    migrateBatch(buffer, v2Ttl, report);
                    buffer.clear();
                }
            }
            if (!buffer.isEmpty()) {
                migrateBatch(buffer, v2Ttl, report);
            }
        }
        return report;
    }

    /**
     * 单 key 迁移——幂等：已有 v2 则不覆盖。
     */
    public boolean migrateOneKey(String v1Key, Duration v2Ttl) {
        if (!v1Key.startsWith(L409Keys.MIGRATE_V1_PREFIX)) {
            throw new IllegalArgumentException("仅支持 v1 前缀的 key：" + v1Key);
        }
        String userId = v1Key.substring(L409Keys.MIGRATE_V1_PREFIX.length());
        String v2Key = L409Keys.migrateV2Key(userId);

        // 幂等检查
        Boolean existsV2 = template.hasKey(v2Key);
        if (Boolean.TRUE.equals(existsV2)) {
            return false; // 已有 v2，跳过
        }

        String v1Value = template.opsForValue().get(v1Key);
        if (v1Value == null) {
            return false; // 期间被删，跳过
        }
        String v2Value = convertV1ToV2(v1Value, userId);
        template.opsForValue().set(v2Key, v2Value, v2Ttl);
        return true;
    }

    /**
     * 验证迁移：v2 是否存在且符合预期。
     */
    public boolean verifyMigration(String userId) {
        String v2 = template.opsForValue().get(L409Keys.migrateV2Key(userId));
        return v2 != null && v2.startsWith("{\"uid\":\"" + userId + "\"");
    }

    public void clearMigrationData(List<String> userIds) {
        List<String> keys = new ArrayList<>(userIds.size() * 2);
        for (String uid : userIds) {
            keys.add(L409Keys.migrateV1Key(uid));
            keys.add(L409Keys.migrateV2Key(uid));
        }
        template.delete(keys);
    }

    private void migrateBatch(List<String> v1Keys, Duration v2Ttl, MigrateReport report) {
        for (String v1Key : v1Keys) {
            report.scanned++;
            try {
                boolean migrated = migrateOneKey(v1Key, v2Ttl);
                if (migrated) report.migrated++;
                else report.skipped++;
            } catch (Exception e) {
                report.failed++;
                report.failedKeys.add(v1Key);
                // 不要吞异常——记录后继续，让任务可重跑
                System.err.println("[L409] migrate failed: " + v1Key + " -> " + e.getMessage());
            }
        }
    }

    /**
     * 简单 schema 转换：plain string → JSON。生产里这里是真正的业务逻辑。
     */
    private String convertV1ToV2(String v1Value, String userId) {
        return "{\"uid\":\"" + userId + "\",\"raw\":\"" + v1Value + "\",\"ver\":2}";
    }
}
