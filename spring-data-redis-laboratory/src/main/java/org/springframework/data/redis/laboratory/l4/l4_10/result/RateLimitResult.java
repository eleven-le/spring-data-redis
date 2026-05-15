package org.springframework.data.redis.laboratory.l4.l4_10.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 滑动窗口限流返回值。
 * <p>
 * 字段直接对应 HTTP RateLimit 头:
 * <pre>
 *   X-RateLimit-Limit:     capacity
 *   X-RateLimit-Remaining: remaining
 *   X-RateLimit-Reset:     ts + retryAfterMs
 *   Retry-After (ms):      retryAfterMs
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RateLimitResult {
    public boolean allowed;
    public int code;
    public String msg;
    public Long used;
    public Long capacity;
    public Long remaining;
    public Long oldestTs;
    public Long retryAfterMs;
    public Long ts;

    public boolean isAllowed() { return allowed; }

    @Override public String toString() {
        return String.format("RateLimitResult{allowed=%s, used=%s/%s, remaining=%s, retryAfterMs=%s, oldestTs=%s, ts=%s}",
                allowed, used, capacity, remaining, retryAfterMs, oldestTs, ts);
    }
}
