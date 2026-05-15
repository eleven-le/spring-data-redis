package org.springframework.data.redis.laboratory.l4.l4_04.zset;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.laboratory.l4.l4_04.L404Keys;

import java.util.Set;

/**
 * 优先级任务队列：score = priority（数字越大越优先）。
 * 比 List 队列灵活：可以"插队"高优先级任务。
 * <p>
 * 仍然存在并发抢占重复消费的风险（同 {@link L404DelayQueueZSetScenario}）。
 * 真实业务建议：
 * - 短任务、可重入：naive 写法 + 业务幂等兜底。
 * - 严格一次：Lua 抢占 + 任务状态机（INIT → DOING → DONE，DOING 才算已抢）。
 * - 跨进程级：上 MQ + 优先级队列特性（如 RabbitMQ priority queue）。
 */
public class L404PriorityQueueZSetScenario {

    private final ZSetOperations<String, String> ops;

    public L404PriorityQueueZSetScenario(StringRedisTemplate template) {
        this.ops = template.opsForZSet();
    }

    public Boolean addTask(String taskId, double priority) {
        return ops.add(L404Keys.PRIORITY_Q, taskId, priority);
    }

    /**
     * 看一眼最高优先级 N 个（不消费）。
     */
    public Set<String> getHighestPriorityTasks(int limit) {
        return ops.reverseRange(L404Keys.PRIORITY_Q, 0, limit - 1);
    }

    public Long removeTask(String taskId) {
        return ops.remove(L404Keys.PRIORITY_Q, taskId);
    }

    /**
     * 抢占式取最高优先级一个。返回 null 表示没抢到。
     */
    public String pollHighestPriorityTaskNaive() {
        Set<String> top = ops.reverseRange(L404Keys.PRIORITY_Q, 0, 0);
        if (top == null || top.isEmpty()) return null;
        String taskId = top.iterator().next();
        Long removed = ops.remove(L404Keys.PRIORITY_Q, taskId);
        return (removed != null && removed > 0) ? taskId : null;
    }
}
