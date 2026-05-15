package org.springframework.data.redis.laboratory.l4.l4_03.string;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.laboratory.l4.l4_03.L403Keys;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 短信验证码场景。
 * <p>
 * key 设计：
 * <ul>
 *   <li>l4:03:sms:code:{phone}    —— 验证码本体，TTL 5 分钟</li>
 *   <li>l4:03:sms:limit:{phone}   —— 1 分钟内的发送次数，TTL 60 秒</li>
 * </ul>
 * <p>
 * 真实业务还需要：图形验证码、IP 限流、设备指纹、风控、短信供应商回调等，
 * 这里只示范 Redis 这一层应该承担什么。
 */
public class L403SmsCodeScenario {

    /**
     * 验证码 5 分钟有效
     */
    public static final Duration CODE_TTL = Duration.ofMinutes(5);
    /**
     * 1 分钟最多 3 条
     */
    public static final Duration LIMIT_WINDOW = Duration.ofMinutes(1);
    public static final long LIMIT_PER_WINDOW = 3L;

    private final ValueOperations<String, String> ops;
    private final StringRedisTemplate template;

    public L403SmsCodeScenario(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForValue();
    }

    private static String codeKey(String phone) {
        return L403Keys.SMS_CODE + phone;
    }

    private static String limitKey(String phone) {
        return L403Keys.SMS_LIMIT + phone;
    }

    /**
     * 发送验证码。
     * 步骤：
     * 1) 限流计数 +1，并在第一次自增时设置窗口 TTL；
     * 2) 超限直接拒绝；
     * 3) 把 6 位验证码写到 Redis，覆盖式（重发会刷新 TTL）。
     */
    public String sendCode(String phone) {
        if (!checkSendLimit(phone)) {
            throw new IllegalStateException("发送过于频繁，请稍后再试: " + phone);
        }
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        ops.set(codeKey(phone), code, CODE_TTL);
        return code;
    }

    /**
     * 限流计数。
     * 注意：先 increment 再 expire 不是严格原子；这里用 "首次写入再设 TTL"，
     * 在并发下最坏情况是首次窗口的 TTL 被多设一次，业务可接受。
     * 真正想要严格原子可以走 Lua / pipeline。
     */
    public boolean checkSendLimit(String phone) {
        String key = limitKey(phone);
        Long count = ops.increment(key);
        if (count != null && count == 1L) {
            template.expire(key, LIMIT_WINDOW);
        }
        return count != null && count <= LIMIT_PER_WINDOW;
    }

    /**
     * 校验验证码：成功后立即删除，避免被重放。
     */
    public boolean verifyCode(String phone, String code) {
        String stored = ops.get(codeKey(phone));
        if (stored == null || !stored.equals(code)) {
            return false;
        }
        template.delete(codeKey(phone));
        return true;
    }

    public void clearCode(String phone) {
        template.delete(codeKey(phone));
        template.delete(limitKey(phone));
    }
}
