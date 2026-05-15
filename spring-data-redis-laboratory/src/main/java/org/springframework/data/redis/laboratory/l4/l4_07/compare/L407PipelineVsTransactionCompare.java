package org.springframework.data.redis.laboratory.l4.l4_07.compare;

import org.springframework.data.redis.laboratory.l4.l4_07.pipeline.L407Pipelines;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * Pipeline vs Transaction（MULTI/EXEC）对比。
 * <p>
 * <b>核心结论</b>：
 *   - Pipeline 不是事务；它只是"客户端把命令打包发出去"；
 *   - MULTI/EXEC 是事务：MULTI 开启 + 多条命令入队 + EXEC 一次性提交；
 *     入队期间其他客户端命令不会插队。但仍不支持回滚，错误命令不会撤销前面的成功命令；
 *   - WATCH 提供"乐观锁"语义，是 MULTI/EXEC 的可观察前提；
 *   - 详细 Transaction 实战放在 L4-08。
 */
public class L407PipelineVsTransactionCompare {

    private final StringRedisTemplate template;

    public L407PipelineVsTransactionCompare(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * Pipeline 不是事务示例。
     * <p>
     * 这两条 INCR 期间，其他客户端的任何命令都可能被 Redis 服务端插队执行。
     */
    public List<Object> pipelineNotTransactionExample(String counterKey1, String counterKey2) {
        return L407Pipelines.run(template, ops -> {
            ops.opsForValue().increment(counterKey1);
            ops.opsForValue().increment(counterKey2);
            return null;
        });
    }

    /**
     * 占位：Transaction 写法（详细实现放到 L4-08）。
     * <p>
     * 写法概览：
     * <pre>
     * template.execute(new SessionCallback&lt;...&gt;() {
     *     public Object execute(RedisOperations ops) {
     *         ops.multi();
     *         ops.opsForValue().increment(k1);
     *         ops.opsForValue().increment(k2);
     *         return ops.exec();
     *     }
     * });
     * </pre>
     */
    public String transactionPlaceholder() {
        return "见 L4-08 Transaction 章节实战。MULTI/EXEC 期间其他客户端不会插队，但 Redis 事务不支持回滚。";
    }
}
