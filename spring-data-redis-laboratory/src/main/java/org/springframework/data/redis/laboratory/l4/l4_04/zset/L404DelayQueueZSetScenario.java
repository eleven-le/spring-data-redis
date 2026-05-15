package org.springframework.data.redis.laboratory.l4.l4_04.zset;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.laboratory.l4.l4_04.L404Keys;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 轻量延迟队列：member = 任务 ID，score = 期望执行时间戳（毫秒）。
 * 消费者轮询 ZRANGEBYSCORE -inf .. now，取出到期任务并 ZREM 删除。
 * <p>
 * 关键安全风险：
 * - rangeByScore + remove 是两步，多个消费者并发时同一个 taskId 会被两个进程都拿到 → 重复消费。
 * - {@link #pollDueTasksNaive(int)} 演示这种 naive 写法（仅供学习对比）。
 * - 真正生产应该用 Lua 把 ZRANGEBYSCORE + ZREM 包成一个脚本（见 L4-10），保证抢占原子。
 * - 或者上 RocketMQ / RabbitMQ 的延迟消息，本质更可靠。
 */
public class L404DelayQueueZSetScenario {

    private final StringRedisTemplate template;
    private final ZSetOperations<String, String> ops;

    public L404DelayQueueZSetScenario(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForZSet();
    }

    /**
     * 投递一个延迟任务。delay = 多久后到期。
     */
    public Boolean addTask(String taskId, Duration delay) {
        long fireAt = System.currentTimeMillis() + delay.toMillis();
        return ops.add(L404Keys.DELAY_QUEUE, taskId, fireAt);
    }

    /**
     * 看一眼到期任务（不消费）。
     */
    public Set<String> getDueTasks(int limit) {
        Set<String> got = ops.rangeByScore(L404Keys.DELAY_QUEUE, 0, System.currentTimeMillis(), 0, limit);
        return got == null ? Collections.emptySet() : got;
    }

    public Long removeTask(String taskId) {
        return ops.remove(L404Keys.DELAY_QUEUE, taskId);
    }

    /**
     * NAIVE 写法 —— 教学用，演示并发重复消费风险。
     * 步骤：1) ZRANGEBYSCORE 取一批；2) 对每个 ZREM。两步之间另一消费者可能也取到。
     */
    public Set<String> pollDueTasksNaive(int limit) {
        Set<String> due = ops.rangeByScore(L404Keys.DELAY_QUEUE, 0, System.currentTimeMillis(), 0, limit);
        if (due == null || due.isEmpty()) return Collections.emptySet();
        Set<String> won = new LinkedHashSet<>();
        for (String id : due) {
            Long removed = ops.remove(L404Keys.DELAY_QUEUE, id);
            // 只有 ZREM 真的删到了（返回 1），才算抢到，避免别的消费者已经抢走。
            if (removed != null && removed > 0) {
                won.add(id);
            }
        }
        return won;
    }

    /**
     * 同上但显式打印警告 —— 提示正经生产请走 Lua。
     */
    public Set<String> pollDueTasksWithWarning(int limit) {
        System.out.println("[WARN] naive ZRANGEBYSCORE+ZREM 不是严格原子，生产请用 Lua 抢占。");
        return pollDueTasksNaive(limit);
    }

    public void clear() {
        template.delete(L404Keys.DELAY_QUEUE);
    }
}
