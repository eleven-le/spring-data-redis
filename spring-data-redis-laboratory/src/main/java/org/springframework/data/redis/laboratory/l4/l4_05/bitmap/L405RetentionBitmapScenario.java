package org.springframework.data.redis.laboratory.l4.l4_05.bitmap;

import org.springframework.data.redis.connection.RedisStringCommands.BitOperation;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_05.L405Keys;

import java.time.Duration;
import java.time.LocalDate;

/**
 * 场景 5.3：连续活跃 / 留存分析（基于每日活跃 Bitmap）。
 * <p>
 * 思路：
 * <ul>
 *   <li>{@link BitOperation#AND}：多天 Bitmap 按位与 → 每天都活跃的人</li>
 *   <li>{@link BitOperation#OR}：多天 Bitmap 按位或 → 至少有一天活跃的人</li>
 * </ul>
 * <p>
 * 边界：在线 BITOP 跨大量天数会阻塞主线程。Redis Bitmap 适合在线轻量分析（几天到几十天），
 * 大规模留存（年维度 / 多维度交叉）请走离线数仓（Spark / Doris / ClickHouse）。
 */
public class L405RetentionBitmapScenario {

    public static final Duration RESULT_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate template;

    public L405RetentionBitmapScenario(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * 连续活跃用户数（startDate 起 days 天，每天都活跃才计入）。
     */
    public long countContinuousActive(LocalDate startDate, int days) {
        String resultKey = createAndResultKey(startDate, days);
        byte[] rawKey = template.getStringSerializer().serialize(resultKey);
        if (rawKey == null) {
            return 0;
        }
        Long c = template.execute((RedisCallback<Long>) conn ->
                conn.stringCommands().bitCount(rawKey));
        return c == null ? 0 : c;
    }

    /**
     * 多日任意活跃用户数（OR）。
     */
    public long countAnyActive(LocalDate startDate, int days) {
        String resultKey = createOrResultKey(startDate, days);
        byte[] rawKey = template.getStringSerializer().serialize(resultKey);
        if (rawKey == null) {
            return 0;
        }
        Long c = template.execute((RedisCallback<Long>) conn ->
                conn.stringCommands().bitCount(rawKey));
        return c == null ? 0 : c;
    }

    /**
     * AND 结果 key —— 用于 debug 时直接看中间结果。
     */
    public String createAndResultKey(LocalDate startDate, int days) {
        return computeBitOp(BitOperation.AND, startDate, days, "and");
    }

    /**
     * OR 结果 key。
     */
    public String createOrResultKey(LocalDate startDate, int days) {
        return computeBitOp(BitOperation.OR, startDate, days, "or");
    }

    private String computeBitOp(BitOperation op, LocalDate startDate, int days, String tag) {
        if (days <= 0) {
            throw new IllegalArgumentException("days 必须 >0");
        }
        String dest = L405Keys.tempBitOpResult(tag + ":"
                + L405Keys.formatDay(startDate) + "-" + days);
        byte[] rawDest = template.getStringSerializer().serialize(dest);
        byte[][] rawSources = new byte[days][];
        for (int i = 0; i < days; i++) {
            String src = L405Keys.dailyActive(startDate.plusDays(i));
            rawSources[i] = template.getStringSerializer().serialize(src);
        }
        template.execute((RedisCallback<Long>) conn ->
                conn.stringCommands().bitOp(op, rawDest, rawSources));
        template.expire(dest, RESULT_TTL);
        return dest;
    }
}
