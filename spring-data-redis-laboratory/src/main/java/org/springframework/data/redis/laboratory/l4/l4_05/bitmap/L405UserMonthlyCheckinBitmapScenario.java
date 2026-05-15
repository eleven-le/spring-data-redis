package org.springframework.data.redis.laboratory.l4.l4_05.bitmap;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.laboratory.l4.l4_05.L405Keys;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 场景 5.1：用户月度签到 Bitmap。
 * <p>
 * key：{@code l4:05:bitmap:checkin:{userId}:{yyyyMM}}
 * <br>
 * offset：dayOfMonth - 1（<b>下标从 0 开始</b>，新手最容易踩这条）
 * <p>
 * 内存对比：每个用户每月 31 bit ≈ 4 字节（外加 String 元数据约 50 字节）。
 * 1000 万用户每月签到，总内存量级 ~500MB，比 Hash 存日期 → 状态省一个数量级。
 * <p>
 * 跨月：业务自己按 yyyyMM 切 key，本类只负责"单月"。要看连续签到（跨月）请在
 * 应用层把多个月的 Bitmap 拉回拼接，或单独维护连续天数 String key（INCR / RESET）。
 */
public class L405UserMonthlyCheckinBitmapScenario {

    /**
     * 签到月度 key 默认保留 13 个月（多保留 1 个月做月初对账）。
     */
    public static final Duration TTL = Duration.ofDays(400);

    private final StringRedisTemplate template;
    private final ValueOperations<String, String> ops;

    public L405UserMonthlyCheckinBitmapScenario(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForValue();
    }

    /**
     * 标记某天签到。date 决定 key（yyyyMM）和 offset（dayOfMonth - 1）。
     */
    public void checkin(String userId, LocalDate date) {
        String key = L405Keys.monthlyCheckin(userId, YearMonth.from(date));
        long offset = date.getDayOfMonth() - 1L;
        ops.setBit(key, offset, true);
        template.expire(key, TTL);
    }

    /**
     * 判断某天是否签到。
     */
    public boolean hasCheckedIn(String userId, LocalDate date) {
        String key = L405Keys.monthlyCheckin(userId, YearMonth.from(date));
        long offset = date.getDayOfMonth() - 1L;
        Boolean b = ops.getBit(key, offset);
        return Boolean.TRUE.equals(b);
    }

    /**
     * 当月签到天数（BITCOUNT）。
     */
    public long countMonthlyCheckin(String userId, YearMonth yearMonth) {
        String key = L405Keys.monthlyCheckin(userId, yearMonth);
        byte[] rawKey = template.getStringSerializer().serialize(key);
        if (rawKey == null) {
            return 0;
        }
        Long c = template.execute((RedisCallback<Long>) conn ->
                conn.stringCommands().bitCount(rawKey));
        return c == null ? 0 : c;
    }

    /**
     * 返回当月每天的签到布尔数组（length = 当月天数）。仅用于演示，生产读 Bitmap 应配合 BITFIELD。
     */
    public List<Boolean> getMonthlyCheckinBits(String userId, YearMonth yearMonth) {
        String key = L405Keys.monthlyCheckin(userId, yearMonth);
        int days = yearMonth.lengthOfMonth();
        List<Boolean> bits = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            Boolean b = ops.getBit(key, i);
            bits.add(Boolean.TRUE.equals(b));
        }
        return bits;
    }

    public void clearMonthlyCheckin(String userId, YearMonth yearMonth) {
        template.delete(L405Keys.monthlyCheckin(userId, yearMonth));
    }
}
