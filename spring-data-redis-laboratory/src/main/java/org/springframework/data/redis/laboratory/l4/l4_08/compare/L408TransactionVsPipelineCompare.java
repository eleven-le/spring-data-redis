package org.springframework.data.redis.laboratory.l4.l4_08.compare;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.L408Keys;
import org.springframework.data.redis.laboratory.l4.l4_08.transaction.L408Transactions;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis 事务 vs Pipeline 对比。
 * <p>
 * 关键区别：
 * <ul>
 *   <li>Pipeline：减少 RTT。命令在 server 侧不要求"原子"，其他客户端命令可能穿插；</li>
 *   <li>Transaction：MULTI 后命令排队，EXEC 顺序执行，等价"打包原子"；</li>
 *   <li>不要为了"批量执行"用事务——批量无依赖应当用 Pipeline。</li>
 * </ul>
 */
public class L408TransactionVsPipelineCompare {

    private final StringRedisTemplate template;

    public L408TransactionVsPipelineCompare(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * 事务版：100 条 SET 进 multi/exec。
     */
    public List<Object> transactionCommandQueueExample() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            keys.add(L408Keys.txLab(1000 + i));
        }
        return L408Transactions.runTxString(template, ops -> {
            ops.multi();
            for (int i = 0; i < keys.size(); i++) {
                ops.opsForValue().set(keys.get(i), "tx-v-" + i);
            }
            return ops.exec();
        });
    }

    /**
     * Pipeline 版：100 条 SET 进一次 RTT。
     */
    public List<Object> pipelineBatchRttExample() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            keys.add(L408Keys.txLab(2000 + i));
        }
        return template.executePipelined((org.springframework.data.redis.core.SessionCallback<Object>) new org.springframework.data.redis.core.SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(org.springframework.data.redis.core.RedisOperations<K, V> ops) {
                StringRedisTemplate t = template;
                for (int i = 0; i < keys.size(); i++) {
                    ((org.springframework.data.redis.core.RedisOperations<String, String>) ops)
                            .opsForValue().set(keys.get(i), "pipe-v-" + i);
                }
                return null;
            }
        });
    }

    public void explainDifference() {
        System.out.println("=== Transaction vs Pipeline ===");
        System.out.println("- Pipeline 关注吞吐：1 个 RTT 发 N 条命令、1 次接收 N 个回复；");
        System.out.println("  其他客户端命令可能在你这批命令中间被服务端处理；");
        System.out.println("- Transaction 关注顺序：MULTI 后命令排队，EXEC 一次性顺序执行，");
        System.out.println("  期间服务端不会插入其他客户端命令；");
        System.out.println("- 二者可以叠加（pipelined transaction），但通常不建议混用；");
        System.out.println("- 选型口诀：批量无依赖 → Pipeline；需要打包原子 → Transaction（或 Lua）。");
    }

    public void cleanup() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 100; i++) keys.add(L408Keys.txLab(1000 + i));
        for (int i = 0; i < 100; i++) keys.add(L408Keys.txLab(2000 + i));
        template.delete(keys);
    }
}
