package org.springframework.data.redis.laboratory.l4.l4_07.compare;

import org.springframework.data.redis.laboratory.l4.l4_07.pipeline.L407Pipelines;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

/**
 * Pipeline vs Lua 对比（仅占位 + 概念演示）。
 * <p>
 * <b>核心结论</b>：
 *   - Pipeline 减 RTT，不保证多条命令的原子性；
 *   - Lua 在 Redis 服务端单线程执行整段脚本，天然原子；
 *   - 复合业务逻辑（库存扣减 + 中奖记录 + 用户标记）必须用 Lua，不能用 Pipeline。
 * <p>
 * 真正的 Lua 实战放在 L4-10。本类只演示一个"误用"反例。
 */
public class L407PipelineVsLuaCompare {

    private final StringRedisTemplate template;

    public L407PipelineVsLuaCompare(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * <b>反例</b>：用 Pipeline 试图实现"先扣库存再写中奖"——这不是原子。
     * <p>
     * 真实业务里，stockKey 的扣减和 winnerKey 的写入之间，可能被其他客户端"看到中间态"，
     * 比如另一个并发请求看到 stock 扣完了但中奖记录还没写，错误地认为活动已结束。
     * <p>
     * 这里仅演示能跑，不代表业务正确。
     */
    public List<Object> pipelineTryCompositeOperation(String stockKey, String winnerKey, String userId) {
        template.opsForValue().setIfAbsent(stockKey, "10", Duration.ofMinutes(5));
        return L407Pipelines.run(template, ops -> {
            ops.opsForValue().decrement(stockKey);
            ops.opsForList().leftPush(winnerKey, userId);
            // 这里就算 stock 已经被别的请求扣到 0，pipeline 这一段也不会回滚 leftPush。
            return null;
        });
    }

    /**
     * 占位：Lua 写法（详细实现放到 L4-10）。
     * <p>
     * 真正的 Lua 脚本大概长这样（伪代码）：
     * <pre>
     * local stock = tonumber(redis.call('GET', KEYS[1]))
     * if stock and stock > 0 then
     *   redis.call('DECR', KEYS[1])
     *   redis.call('LPUSH', KEYS[2], ARGV[1])
     *   return 1
     * end
     * return 0
     * </pre>
     */
    public String luaAtomicCompositeOperationPlaceholder() {
        return "见 L4-10 Lua 章节实战。Lua 在服务端单线程执行整段，天然原子。";
    }
}
