package org.springframework.data.redis.laboratory.l4.l4_10.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.laboratory.l4.l4_10.config.L410RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_10.result.CounterThresholdResult;
import org.springframework.data.redis.laboratory.l4.l4_10.result.RateLimitResult;
import org.springframework.data.redis.laboratory.l4.l4_10.scenario.L410CounterThresholdLuaScenario;
import org.springframework.data.redis.laboratory.l4.l4_10.scenario.L410SlidingWindowRateLimitLuaScenario;

import java.time.Duration;

/**
 * 生产级滑动窗口限流 + 计数器阈值演示。
 * <p>
 * 输出包括:每次请求的 used / capacity / remaining / retryAfterMs 全套字段,
 * 计数器输出 current / threshold / distance / alarm / ttlMs。
 */
public class L410RateLimitDebugMain {

    public static void main(String[] args) throws InterruptedException {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(L410RedisConfig.class)) {

            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);
            ObjectMapper mapper = ctx.getBean(ObjectMapper.class);
            @SuppressWarnings("unchecked")
            RedisScript<String> rate = (RedisScript<String>) ctx.getBean("slidingWindowRateLimitScript");
            @SuppressWarnings("unchecked")
            RedisScript<String> counter = (RedisScript<String>) ctx.getBean("counterThresholdScript");

            // ===== 滑动窗口限流:每秒最多 3 次 =====
            L410SlidingWindowRateLimitLuaScenario rl =
                    new L410SlidingWindowRateLimitLuaScenario(template, rate, mapper);
            String userId = "u-debug";
            String api = "/order/create";
            rl.clearLimit(userId, api);

            System.out.println("=== 第 1 秒爆发 5 次,容量 3 ===");
            for (int i = 0; i < 5; i++) {
                RateLimitResult r = rl.tryAcquire(userId, api, 3, Duration.ofSeconds(1));
                System.out.println("req#" + i + " -> " + r);
            }
            System.out.println("\n=== 等待 1.1 秒后窗口滑出 ===");
            Thread.sleep(1100);
            RateLimitResult after = rl.tryAcquire(userId, api, 3, Duration.ofSeconds(1));
            System.out.println("after-sleep -> " + after);
            rl.clearLimit(userId, api);

            // ===== 计数器阈值:每日领券最多 3 次,告警水位 80% =====
            L410CounterThresholdLuaScenario ct =
                    new L410CounterThresholdLuaScenario(template, counter, mapper);
            String action = "claim_coupon";
            ct.clearCounter(userId, action);
            System.out.println("\n=== 每日领券上限 3,5 次连续尝试,告警水位 80% ===");
            for (int i = 0; i < 5; i++) {
                CounterThresholdResult r = ct.tryIncrease(userId, action, 1, 3, Duration.ofMinutes(10), 80);
                System.out.println("counter#" + i + " -> " + r);
            }
            ct.clearCounter(userId, action);
        }
    }
}
