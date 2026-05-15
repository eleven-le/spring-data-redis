package org.springframework.data.redis.laboratory.l4.l4_04.set;

import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_04.L404Keys;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * 每日签到去重：每天一个 Set，签到一次 SADD，统计去重直接 SCARD。
 * <p>
 * 真实业务的边界：
 * 1) 用户量百万级以下，Set 完全够用，每个 member 16 字节左右。
 * 2) 用户量千万级以上，Set 内存吃紧，强烈建议 Bitmap：每个 userId 1 bit，1 亿用户 ~12MB。
 * 但 Bitmap 要求 userId 是稠密整数 ID，稀疏 ID 反而浪费。
 * 3) "连续签到天数" 不要在 Set 上算，用 String key 存 streak count，每天 INCR 或重置。
 * 4) 签到 Set 必须设过期时间，否则历史 Set 永久躺在 Redis 里。
 */
public class L404DailyCheckinSetScenario {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Duration TTL = Duration.ofDays(40); // 过期 = 业务统计窗口 + 安全冗余

    private final StringRedisTemplate template;
    private final SetOperations<String, String> ops;

    public L404DailyCheckinSetScenario(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForSet();
    }

    private String todayKey() {
        return L404Keys.CHECKIN + LocalDate.now().format(DAY_FMT);
    }

    /**
     * 签到。返回 true 表示首次签到（去重命中）。
     */
    public boolean checkin(String userId) {
        String key = todayKey();
        Long added = ops.add(key, userId);
        template.expire(key, TTL); // 每次签到顺手续命，避免漏设过期
        return added != null && added > 0;
        // 断点: DefaultSetOperations.add → connection.setCommands().sAdd
    }

    public Boolean hasCheckedIn(String userId) {
        return ops.isMember(todayKey(), userId);
    }

    /**
     * 当天签到 UV。
     */
    public Long countToday() {
        return ops.size(todayKey());
    }

    /**
     * 开发调试用，生产严禁直接 SMEMBERS 海量 Set。
     */
    public Set<String> getTodayUsers() {
        return ops.members(todayKey());
    }

    public void clearToday() {
        template.delete(todayKey());
    }
}
