package org.springframework.data.redis.laboratory.l4.l4_05.hyperloglog;

import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_05.L405Keys;

import java.time.Duration;
import java.time.LocalDate;

/**
 * 场景 3.1：首页每日 UV 统计。
 * <p>
 * key：{@code l4:05:hll:uv:home:{yyyyMMdd}}
 * <p>
 * 为什么不用 Set 存 userId：
 * <ul>
 *   <li>10 万用户 → Set ~1.6MB；HLL 12KB</li>
 *   <li>500 万用户 → Set ~80MB；HLL 仍 12KB</li>
 *   <li>HLL 用 0.81% 误差换"不随用户增长的内存上限"</li>
 * </ul>
 * <p>
 * <b>本类只产生近似 UV，不能取回访问者明细</b>。如果业务需要明细回溯，请配合日志 / DB / 数仓。
 */
public class L405DailyUvHyperLogLogScenario {

    /** UV key 默认保留 90 天（季度回溯）。 */
    public static final Duration TTL = Duration.ofDays(90);

    private final StringRedisTemplate template;
    private final HyperLogLogOperations<String, String> hll;

    public L405DailyUvHyperLogLogScenario(StringRedisTemplate template) {
        this.template = template;
        this.hll = template.opsForHyperLogLog();
    }

    /**
     * 记录一次首页访问。userIdOrDeviceId 已登录用 userId，未登录用 deviceId / 设备指纹。
     * 内部命令：PFADD + EXPIRE（首次写入设 TTL，幂等）。
     */
    public void recordHomeVisit(String userIdOrDeviceId) {
        recordHomeVisit(userIdOrDeviceId, LocalDate.now());
    }

    public void recordHomeVisit(String userIdOrDeviceId, LocalDate date) {
        String key = L405Keys.homeUv(date);
        hll.add(key, userIdOrDeviceId);
        template.expire(key, TTL);
    }

    /** 当日 UV（近似）。 */
    public long getTodayUv() {
        return getUvByDate(LocalDate.now());
    }

    public long getUvByDate(LocalDate date) {
        Long count = hll.size(L405Keys.homeUv(date));
        return count == null ? 0 : count;
    }

    /** 多天合并 UV（PFCOUNT key1 key2 ... 不写回）。 */
    public long getRangeUv(LocalDate startInclusive, LocalDate endInclusive) {
        if (endInclusive.isBefore(startInclusive)) {
            return 0;
        }
        int days = (int) (endInclusive.toEpochDay() - startInclusive.toEpochDay()) + 1;
        String[] keys = new String[days];
        for (int i = 0; i < days; i++) {
            keys[i] = L405Keys.homeUv(startInclusive.plusDays(i));
        }
        Long count = hll.size(keys);
        return count == null ? 0 : count;
    }

    public void clearTodayUv() {
        template.delete(L405Keys.homeUv(LocalDate.now()));
    }
}
