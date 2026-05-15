package org.springframework.data.redis.laboratory.l4.l4_06.idempotent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.laboratory.l4.l4_06.L406Keys;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 06.05 幂等设计
 *
 * 真实场景：
 *  优惠券消费者拿到一条 ORDER_COUPON_APPLIED 事件。Redis Stream 保证至少一次投递，
 *  Pending 恢复 / 客户端重连 / autoClaim 都会让同一条消息重复到达。
 *  必须保证：同一个 (eventId, group) 只有一次会真正核销优惠券。
 *
 * 设计：
 *  - key：idempotent:stream:{group}:{eventId}
 *  - 状态机：
 *      未存在 → SET NX EX 抢锁，状态 = PROCESSING；
 *      值 = PROCESSING → 别人正在处理（或上次没及时清理）；
 *      值 = COMPLETED → 已成功处理，直接 ACK；
 *      值 = FAILED：上次失败留下的"墓碑"，本次允许重试。
 *
 * 关键 Spring Data Redis API：
 *  - {@link ValueOperations#setIfAbsent(Object, Object, Duration)} → SET NX EX
 *  - {@link ValueOperations#get} → 读状态
 *  - {@link ValueOperations#set(Object, Object, Duration)} → 标 COMPLETED / FAILED
 *
 * 建议断点：
 *  - {@link ValueOperations#setIfAbsent} —— 看 SDR 怎么把 NX EX 翻译成 SetCommand；
 *  - LettuceStringCommands#set —— 看真实 RESP 协议下达。
 *
 * 新手避坑：
 *  - TTL 设太短：Pending 恢复时 key 已经过期，幂等失效；
 *  - TTL 设太长：FAILED 墓碑长期占内存；建议 24~72h；
 *  - 抢锁成功 ≠ 业务成功：业务失败必须 markFailed，否则下次会被 LOCKED_BY_OTHER 阻塞 TTL 全程；
 *  - 幂等 key 与业务 key 混在一起：清理时一不小心扫到业务库，惨案。
 *  - 业务幂等还要在数据库唯一键兜底：Redis 是热路径，DB 是冷兜底，双重锁定才能 100%。
 */
@Component
public class StreamConsumeIdempotentService {

    /** 抢锁默认 TTL，等价于"一条消息最长允许处理多久" + 缓冲。 */
    public static final Duration DEFAULT_LOCK_TTL = Duration.ofMinutes(10);

    /** 完成态保留时间：72 小时，足够覆盖大多数 Pending 恢复 + autoClaim 时间窗口。 */
    public static final Duration COMPLETED_TTL = Duration.ofHours(72);

    public static final String STATE_PROCESSING = "PROCESSING";
    public static final String STATE_COMPLETED = "COMPLETED";
    public static final String STATE_FAILED = "FAILED";

    private final StringRedisTemplate redis;

    @Autowired
    public StreamConsumeIdempotentService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 尝试抢占幂等锁。
     *
     *  - FIRST_TIME：业务往下走；
     *  - ALREADY_DONE：直接 ACK；
     *  - LOCKED_BY_OTHER：不 ACK，等下次 redeliver；
     *  - PREVIOUSLY_FAILED：业务往下走（重试），并保留 FAILED 标记直到本次成功覆盖。
     */
    public IdempotentResult tryAcquire(String group, String eventId) {
        return tryAcquire(group, eventId, DEFAULT_LOCK_TTL);
    }

    public IdempotentResult tryAcquire(String group, String eventId, Duration lockTtl) {
        String key = L406Keys.idempotentKey(group, eventId);
        ValueOperations<String, String> ops = redis.opsForValue();

        Boolean acquired = ops.setIfAbsent(key, STATE_PROCESSING, lockTtl);
        if (Boolean.TRUE.equals(acquired)) {
            return IdempotentResult.FIRST_TIME;
        }

        String existing = ops.get(key);
        if (existing == null) {
            // 极小概率：并发判定时刚好过期。重试一次抢锁。
            Boolean retry = ops.setIfAbsent(key, STATE_PROCESSING, lockTtl);
            return Boolean.TRUE.equals(retry) ? IdempotentResult.FIRST_TIME : IdempotentResult.LOCKED_BY_OTHER;
        }
        return switch (existing) {
            case STATE_COMPLETED -> IdempotentResult.ALREADY_DONE;
            case STATE_FAILED -> IdempotentResult.PREVIOUSLY_FAILED;
            default -> IdempotentResult.LOCKED_BY_OTHER;
        };
    }

    /** 业务处理成功 → 标 COMPLETED。后续重复消息走 ALREADY_DONE → 直接 ACK。 */
    public void markCompleted(String group, String eventId) {
        redis.opsForValue().set(L406Keys.idempotentKey(group, eventId), STATE_COMPLETED, COMPLETED_TTL);
    }

    /** 业务处理失败 → 标 FAILED。允许下次重试，避免 LOCKED_BY_OTHER 把消息卡到 TTL 终结。 */
    public void markFailed(String group, String eventId) {
        redis.opsForValue().set(L406Keys.idempotentKey(group, eventId), STATE_FAILED, COMPLETED_TTL);
    }

    /** 调试用：查询当前状态字符串。 */
    public String inspect(String group, String eventId) {
        return redis.opsForValue().get(L406Keys.idempotentKey(group, eventId));
    }
}
