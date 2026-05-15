package org.springframework.data.redis.laboratory.l4.l4_03.string;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.laboratory.l4.l4_03.L403Keys;

import java.time.Duration;

/**
 * 幂等标记场景。
 * <p>
 * 与"分布式锁"的区别：
 * <ul>
 *   <li>分布式锁强调互斥执行 + 释放语义（最终要 DEL）；</li>
 *   <li>幂等标记强调 "同一 requestId 只做一次"，TTL 内拒绝重复，TTL 后自然失效。</li>
 * </ul>
 * <p>
 * 永远用带 TTL 的 setIfAbsent。先 setIfAbsent 再 expire 不是严格原子，
 * 在 setIfAbsent 成功后宕机/网络抖动会留下永久脏 key。
 */
public class L403IdempotentScenario {

    public static final Duration MARK_TTL = Duration.ofMinutes(10);

    private final ValueOperations<String, String> ops;
    private final StringRedisTemplate template;

    public L403IdempotentScenario(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForValue();
    }

    private static String markKey(String requestId) {
        return L403Keys.IDEMPOTENT + requestId;
    }

    /**
     * @return true 表示首次进入，可执行业务；false 表示已经标记过，直接返回上次结果或拒绝。
     */
    public boolean tryMark(String requestId) {
        Boolean ok = ops.setIfAbsent(markKey(requestId), "1", MARK_TTL);
        return Boolean.TRUE.equals(ok);
    }

    public void clearMark(String requestId) {
        template.delete(markKey(requestId));
    }

    /**
     * 业务封装：保证 action 在 TTL 内只执行一次。
     * 注意：业务异常时是否需要 clearMark 取决于业务语义——
     * "扣款下单"这种通常不能清，避免重复扣款；
     * "纯查询"可以清，让用户能重试。
     */
    public boolean executeOnce(String requestId, Runnable action) {
        if (!tryMark(requestId)) {
            return false;
        }
        action.run();
        return true;
    }
}
