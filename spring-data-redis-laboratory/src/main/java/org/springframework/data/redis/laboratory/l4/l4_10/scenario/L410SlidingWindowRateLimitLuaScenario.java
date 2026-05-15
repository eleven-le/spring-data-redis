package org.springframework.data.redis.laboratory.l4.l4_10.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.laboratory.l4.l4_10.L410Keys;
import org.springframework.data.redis.laboratory.l4.l4_10.result.RateLimitResult;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * 生产级·滑动窗口限流(返回剩余配额 + 重试建议时间)。
 * <p>
 * 返回 {@link RateLimitResult} 直接对应 HTTP 限流头:
 * <pre>
 *   X-RateLimit-Limit:     result.capacity
 *   X-RateLimit-Remaining: result.remaining
 *   Retry-After:           result.retryAfterMs (ms)
 * </pre>
 * 边缘场景:Redis 故障时调用方应当 fail-open(默认放行)而不是 fail-close,否则 Redis 抖动直接拖垮全站。
 */
public class L410SlidingWindowRateLimitLuaScenario {

    private final StringRedisTemplate template;
    private final RedisScript<String> script;
    private final ObjectMapper mapper;

    public L410SlidingWindowRateLimitLuaScenario(StringRedisTemplate template,
                                                 RedisScript<String> slidingWindowRateLimitScript,
                                                 ObjectMapper mapper) {
        this.template = template;
        this.script = slidingWindowRateLimitScript;
        this.mapper = mapper;
    }

    public RateLimitResult tryAcquire(String userId, String api, int capacity, Duration window) {
        return tryAcquireWithRequestId(userId, api, UUID.randomUUID().toString(), capacity, window);
    }

    public RateLimitResult tryAcquireWithRequestId(String userId, String api, String requestId,
                                                   int capacity, Duration window) {
        long now = System.currentTimeMillis();
        long winMs = window.toMillis();
        long ttl = window.getSeconds() + 5;

        String json = template.execute(
                script,
                Collections.singletonList(L410Keys.rate(userId, api)),
                String.valueOf(now),
                String.valueOf(winMs),
                String.valueOf(capacity),
                requestId,
                String.valueOf(ttl)
        );
        try {
            return mapper.readValue(json, RateLimitResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Lua 返回 JSON 解析失败:" + json, e);
        }
    }

    public void clearLimit(String userId, String api) {
        template.delete(L410Keys.rate(userId, api));
    }
}
