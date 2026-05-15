package org.springframework.data.redis.laboratory.l4.l4_09;

/**
 * L4-09 章节统一 key 前缀。
 * <p>
 * 集中收口避免裸字符串散落各处；pattern 设计是 Scan 的第一要素：
 * 前缀越清晰，scan 的 match 越精准，扫描浪费越小。
 */
public final class L409Keys {

    /**
     * 章节根前缀，所有实验 key 都以此打头，方便统一清理 / 审计 / 监控。
     */
    public static final String ROOT = "l4:09:";

    public static final String SCAN_DEMO_PREFIX = ROOT + "scan:demo:";       // Scan 基础实验
    public static final String CURSOR_DEMO_PREFIX = ROOT + "scan:cursor:";     // Cursor 生命周期
    public static final String OPTIONS_DEMO_PREFIX = ROOT + "scan:options:";    // ScanOptions

    public static final String HASH_DEMO_KEY = ROOT + "hash:user:behavior";
    public static final String SET_DEMO_KEY = ROOT + "set:like:item";
    public static final String ZSET_DEMO_KEY = ROOT + "zset:rank:hot";

    public static final String CLEAN_DEMO_PREFIX = ROOT + "clean:";
    public static final String ACTIVITY_PREFIX = ROOT + "activity:";
    public static final String MIGRATE_V1_PREFIX = ROOT + "migrate:v1:user:profile:";
    public static final String MIGRATE_V2_PREFIX = ROOT + "migrate:v2:user:profile:";

    public static final String PIPELINE_BATCH_PREFIX = ROOT + "pipeline:batch:";

    private L409Keys() {
    }

    /**
     * activity:{activityId}:* —— 一切活动相关缓存的 pattern 由本工具方法统一构造。
     */
    public static String activityPattern(String activityId) {
        if (activityId == null || activityId.isEmpty() || activityId.contains("*")) {
            // pattern 注入 * 等于让 scan 范围爆炸，必须显式拒绝
            throw new IllegalArgumentException("activityId 必须是非空、不含通配符的纯 id：" + activityId);
        }
        return ACTIVITY_PREFIX + activityId + ":*";
    }

    public static String activityCouponKey(String activityId, String couponId) {
        return ACTIVITY_PREFIX + activityId + ":coupon:" + couponId;
    }

    public static String activityUserKey(String activityId, String userId) {
        return ACTIVITY_PREFIX + activityId + ":user:" + userId;
    }

    public static String activityMetaKey(String activityId) {
        return ACTIVITY_PREFIX + activityId + ":meta";
    }

    public static String migrateV1Key(String userId) {
        return MIGRATE_V1_PREFIX + userId;
    }

    public static String migrateV2Key(String userId) {
        return MIGRATE_V2_PREFIX + userId;
    }
}
