package org.springframework.data.redis.laboratory.l4.l4_09.scenario;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_09.L409Keys;

import java.time.Duration;
import java.util.List;

/**
 * 场景 2：活动结束后批量清理活动相关缓存。
 * <p>
 * <b>业务故事</b>：增长活动 618 大促结束后要清理：
 * <pre>
 * activity:{aid}:meta
 * activity:{aid}:coupon:*
 * activity:{aid}:user:*
 * </pre>
 * 一次活动产出几十万 key。理想做法：上线时就给所有 activity key 设置 TTL，
 * 活动结束后让 Redis 自动过期；Scan 清理只是兜底——给那些"忘了 TTL"的脏 key 兜个底。
 * <p>
 * 关键设计：
 * <ul>
 *   <li>pattern 不能过宽——必须由 activityId 精确组装；</li>
 *   <li>不要在业务代码里手写 "activity:*"，必须用 {@link L409Keys#activityPattern}；</li>
 *   <li>失败 key 单独记录，重试任务幂等；</li>
 *   <li>真实业务把任务托管在调度框架（XXL-Job / Elastic-Job），加并发互斥锁。</li>
 * </ul>
 */
public class L409ActivityCacheCleanScenario {

    private final StringRedisTemplate template;
    private final L409BatchCleanCacheScenario clean;

    public L409ActivityCacheCleanScenario(StringRedisTemplate template) {
        this.template = template;
        this.clean = new L409BatchCleanCacheScenario(template);
    }

    /**
     * 模拟活动期间写入的缓存：meta + N 张 coupon + N 个 user 标记。
     */
    public void prepareActivityCache(String activityId, int couponCount, int userCount, Duration ttl) {
        template.opsForValue().set(L409Keys.activityMetaKey(activityId),
                "{\"name\":\"618\",\"id\":\"" + activityId + "\"}", ttl);
        for (int i = 0; i < couponCount; i++) {
            template.opsForValue().set(L409Keys.activityCouponKey(activityId, "c" + i), "available", ttl);
        }
        for (int i = 0; i < userCount; i++) {
            template.opsForValue().set(L409Keys.activityUserKey(activityId, "u" + i), "joined", ttl);
        }
    }

    /**
     * 清理某个活动的全部缓存。pattern 由 L409Keys 统一构造，避免业务代码硬编码。
     */
    public long cleanActivityCache(String activityId, int scanCount, int batchSize, long sleepMs) {
        String pattern = buildActivityPattern(activityId);
        return clean.cleanByScanAndUnlinkIfSupported(pattern, scanCount, batchSize, sleepMs);
    }

    /**
     * Dry run：只列前 maxKeys 个会被清掉的 key 给运维核对。
     */
    public List<String> dryRunActivityCache(String activityId, int scanCount, int maxKeys) {
        String pattern = buildActivityPattern(activityId);
        return clean.dryRunClean(pattern, scanCount, maxKeys);
    }

    public String buildActivityPattern(String activityId) {
        return L409Keys.activityPattern(activityId);
    }
}
