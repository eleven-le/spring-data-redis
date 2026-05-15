package org.springframework.data.redis.laboratory.l4.l4_05.hyperloglog;

import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * L4-05 HyperLogLog 命令全家桶（基础实验）。
 * <p>
 * 注意：HLL 是<b>近似基数估计</b>算法，标准误差约 0.81%。
 * <ul>
 *   <li>不能取回成员明细</li>
 *   <li>不适合财务结算 / 强精确计费</li>
 *   <li>每个 key 大约固定 12KB</li>
 * </ul>
 * <p>
 * 通用源码链路（Step Into 三次必到底）：
 * <pre>
 * RedisTemplate.opsForHyperLogLog()
 *   → DefaultHyperLogLogOperations.add(...)
 *     → RedisTemplate.execute(callback)
 *       → RedisConnectionUtils.doGetConnection
 *         → LettuceConnection.hyperLogLogCommands().pfAdd(...)
 *           → Lettuce RedisCommands.pfadd(...)
 * </pre>
 */
public class L405HyperLogLogBasicOperationsLab {

    private final StringRedisTemplate template;
    private final HyperLogLogOperations<String, String> ops;

    public L405HyperLogLogBasicOperationsLab(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForHyperLogLog();
    }

    /**
     * PFADD —— 加入成员（自动去重）。
     * SDR API: HyperLogLogOperations.add(K, V...)
     * 断点：DefaultHyperLogLogOperations#add
     * 坑：返回值是"基数是否变化"（0/1），不是"加进去几个"。
     */
    public Long add(String key, String... values) {
        return ops.add(key, values);
    }

    /**
     * PFCOUNT —— 估算单 key 基数。
     * SDR API: HyperLogLogOperations.size(K...)
     * 断点：DefaultHyperLogLogOperations#size
     * 坑：返回值是 long 但只是估算值，不是精确数量。
     */
    public Long size(String key) {
        return ops.size(key);
    }

    /**
     * PFCOUNT key1 key2 ... —— 多 key 合并基数（不写回）。
     * 适合"周 UV / 月 UV / 多个活动联合 UV"快速估算。
     * 坑：单次合并 key 数量过多（数百以上）会拖慢 Redis。
     */
    public Long sizeUnion(String... keys) {
        return ops.size(keys);
    }

    /**
     * PFMERGE —— 多 key 合并基数并写回 destination。
     * SDR API: HyperLogLogOperations.union(destKey, sourceKeys...)
     * 断点：DefaultHyperLogLogOperations#union
     * 坑：destination 会被覆盖；merge 后无法恢复明细。
     */
    public Long union(String destKey, String... sourceKeys) {
        return ops.union(destKey, sourceKeys);
    }

    /**
     * DEL —— 直接删除整个 HLL key（HLL 没有"逐个删元素"的能力）。
     * SDR API: HyperLogLogOperations.delete(K)
     */
    public void delete(String key) {
        ops.delete(key);
    }

    /**
     * 注意：SDR 2.7.18 没有 BoundHyperLogLogOperations（不像 List/Set/ZSet/Hash/Value/Geo 有 Bound 版本）。
     * 业务里如果想要"绑定 key"的语义，可以自己写一个轻量包装类，构造时传入 key + 内部 HyperLogLogOperations，
     * 详见 toushi/BoundResourceOperationsDemo 的 DailyUv 示例。
     */
}
