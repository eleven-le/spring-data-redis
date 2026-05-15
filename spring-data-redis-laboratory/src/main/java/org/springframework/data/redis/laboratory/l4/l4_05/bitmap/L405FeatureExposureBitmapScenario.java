package org.springframework.data.redis.laboratory.l4.l4_05.bitmap;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.laboratory.l4.l4_05.L405Keys;

import java.time.Duration;
import java.time.LocalDate;

/**
 * 场景 5.4：功能曝光状态。
 * <p>
 * key：{@code l4:05:bitmap:exposure:{featureId}:{yyyyMMdd}}
 * <br>
 * offset：userIndex（同 DAU 场景，调用方负责紧凑映射）
 * <p>
 * 用 Bitmap 替代 Set 存"已曝光过的用户"：
 * <ul>
 *   <li>判断"今天是否给这个用户弹过引导浮层" → getBit O(1)</li>
 *   <li>当日总曝光人数 → bitCount</li>
 * </ul>
 * <p>
 * 边界：Bitmap 只能记 0/1。如果业务还要记录"曝光时间 / 渠道 / 版本 / 实验分组" → Bitmap 不够，
 * 需要 Bitmap（用于秒级判重） + 日志/DB（用于明细分析）双链路。
 */
public class L405FeatureExposureBitmapScenario {

    public static final Duration TTL = Duration.ofDays(30);

    private final StringRedisTemplate template;
    private final ValueOperations<String, String> ops;

    public L405FeatureExposureBitmapScenario(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForValue();
    }

    public void markExposed(String featureId, long userIndex, LocalDate date) {
        if (userIndex < 0) {
            throw new IllegalArgumentException("userIndex 必须 >=0");
        }
        String key = L405Keys.featureExposure(featureId, date);
        ops.setBit(key, userIndex, true);
        template.expire(key, TTL);
    }

    public boolean hasExposed(String featureId, long userIndex, LocalDate date) {
        Boolean b = ops.getBit(L405Keys.featureExposure(featureId, date), userIndex);
        return Boolean.TRUE.equals(b);
    }

    public long countExposure(String featureId, LocalDate date) {
        String key = L405Keys.featureExposure(featureId, date);
        byte[] rawKey = template.getStringSerializer().serialize(key);
        if (rawKey == null) {
            return 0;
        }
        Long c = template.execute((RedisCallback<Long>) conn ->
                conn.stringCommands().bitCount(rawKey));
        return c == null ? 0 : c;
    }

    public void clearExposure(String featureId, LocalDate date) {
        template.delete(L405Keys.featureExposure(featureId, date));
    }
}
