package org.springframework.data.redis.laboratory.l4.l4_10.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 计数器阈值脚本返回值。
 * <p>
 * code: 1 放行 / 0 超阈值未写入 / -9 参数非法
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CounterThresholdResult {
    public int code;
    public String msg;
    public Long current;
    public Long threshold;
    public Long delta;
    public Long distance;
    public Long ttlMs;
    public boolean alarm;
    public Long ts;

    public boolean isAllowed() { return code == 1; }

    @Override public String toString() {
        return String.format("CounterThresholdResult{code=%d, msg=%s, current=%s/%s, distance=%s, ttlMs=%s, alarm=%s}",
                code, msg, current, threshold, distance, ttlMs, alarm);
    }
}
