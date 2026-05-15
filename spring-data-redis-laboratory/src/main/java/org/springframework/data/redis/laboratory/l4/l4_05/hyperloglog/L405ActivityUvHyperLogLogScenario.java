package org.springframework.data.redis.laboratory.l4.l4_05.hyperloglog;

import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_05.L405Keys;

import java.time.Duration;
import java.time.LocalDate;

/**
 * 场景 3.2：活动页 UV 统计。
 * <p>
 * key：{@code l4:05:hll:uv:activity:{activityId}:{yyyyMMdd}}
 * <p>
 * 业务边界：
 * <ul>
 *   <li>运营复盘可接受 0.81% 误差 → 用 HLL</li>
 *   <li>结算 / 计费（按 UV 给广告主出账）→ <b>不能</b>只靠 HLL，必须 DB / 数仓</li>
 * </ul>
 * <p>
 * mergeActivityUv 演示 PFMERGE：把多天结果合并到一个新 key，可重复 size 而不再走多 key 计算。
 */
public class L405ActivityUvHyperLogLogScenario {

    public static final Duration TTL = Duration.ofDays(180);

    private final StringRedisTemplate template;
    private final HyperLogLogOperations<String, String> hll;

    public L405ActivityUvHyperLogLogScenario(StringRedisTemplate template) {
        this.template = template;
        this.hll = template.opsForHyperLogLog();
    }

    public void recordVisit(String activityId, String userIdOrDeviceId) {
        recordVisit(activityId, userIdOrDeviceId, LocalDate.now());
    }

    public void recordVisit(String activityId, String userIdOrDeviceId, LocalDate date) {
        String key = L405Keys.activityUv(activityId, date);
        hll.add(key, userIdOrDeviceId);
        template.expire(key, TTL);
    }

    public long getDailyUv(String activityId, LocalDate date) {
        Long c = hll.size(L405Keys.activityUv(activityId, date));
        return c == null ? 0 : c;
    }

    /**
     * 把活动从 startDate 到 endDate（含）的多天 HLL 用 PFMERGE 合并到一个新 key。
     * 返回合并后 key 的近似基数。
     */
    public long mergeActivityUv(String activityId, LocalDate startInclusive, LocalDate endInclusive) {
        if (endInclusive.isBefore(startInclusive)) {
            return 0;
        }
        int days = (int) (endInclusive.toEpochDay() - startInclusive.toEpochDay()) + 1;
        String[] sources = new String[days];
        for (int i = 0; i < days; i++) {
            sources[i] = L405Keys.activityUv(activityId, startInclusive.plusDays(i));
        }
        String dest = L405Keys.PREFIX + "hll:uv:activity:" + activityId
                + ":merged:" + L405Keys.formatDay(startInclusive)
                + "-" + L405Keys.formatDay(endInclusive);
        hll.union(dest, sources);
        template.expire(dest, TTL);
        Long c = hll.size(dest);
        return c == null ? 0 : c;
    }

    public void clearActivityUv(String activityId, LocalDate date) {
        template.delete(L405Keys.activityUv(activityId, date));
    }
}
