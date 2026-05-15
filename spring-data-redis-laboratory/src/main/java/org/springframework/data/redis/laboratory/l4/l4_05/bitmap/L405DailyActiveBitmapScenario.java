package org.springframework.data.redis.laboratory.l4.l4_05.bitmap;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.laboratory.l4.l4_05.L405Keys;

import java.time.Duration;
import java.time.LocalDate;

/**
 * 场景 5.2：App 每日活跃用户 Bitmap（DAU）。
 * <p>
 * key：{@code l4:05:bitmap:active:daily:{yyyyMMdd}}
 * <br>
 * offset：<b>userIndex</b>（不是 userId！）
 * <p>
 * <b>新手雷区</b>：
 * <ul>
 *   <li>不要直接把 userId 当 offset。比如 userId 为 1_000_000_000 的用户 setBit
 *       会立刻把 String 撑到 125MB。一个用户拖崩一个 key。</li>
 *   <li>正确做法：维护 userId → compactIndex 的紧凑映射（递增整数）。userIndex 紧凑
 *       才能让 Bitmap 精准 + 节省内存。</li>
 *   <li>本类把 userIndex 直接当 offset 传入，<b>调用方负责映射</b>。</li>
 * </ul>
 * <p>
 * 和 HLL 对比：
 * <ul>
 *   <li>HLL：近似、不需连续 offset、12KB 固定</li>
 *   <li>Bitmap：精确、需要紧凑 offset、内存随 maxOffset 线性增长</li>
 * </ul>
 */
public class L405DailyActiveBitmapScenario {

    public static final Duration TTL = Duration.ofDays(60);

    private final StringRedisTemplate template;
    private final ValueOperations<String, String> ops;

    public L405DailyActiveBitmapScenario(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForValue();
    }

    /**
     * 标记 userIndex 当天活跃。
     */
    public void markActive(long userIndex, LocalDate date) {
        if (userIndex < 0) {
            throw new IllegalArgumentException("userIndex 必须 >=0：" + userIndex);
        }
        String key = L405Keys.dailyActive(date);
        ops.setBit(key, userIndex, true);
        template.expire(key, TTL);
    }

    /**
     * 判断 userIndex 是否当天活跃。
     */
    public boolean isActive(long userIndex, LocalDate date) {
        Boolean b = ops.getBit(L405Keys.dailyActive(date), userIndex);
        return Boolean.TRUE.equals(b);
    }

    /**
     * 当日 DAU（精确，BITCOUNT）。
     */
    public long countDailyActive(LocalDate date) {
        String key = L405Keys.dailyActive(date);
        byte[] rawKey = template.getStringSerializer().serialize(key);
        if (rawKey == null) {
            return 0;
        }
        Long c = template.execute((RedisCallback<Long>) conn ->
                conn.stringCommands().bitCount(rawKey));
        return c == null ? 0 : c;
    }

    public void clearDailyActive(LocalDate date) {
        template.delete(L405Keys.dailyActive(date));
    }
}
