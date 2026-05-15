package org.springframework.data.redis.laboratory.l4.l4_07.scenario;

import org.springframework.data.redis.laboratory.l4.l4_07.pipeline.L407Pipelines;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 场景 6.3：批量用户行为计数（Pipeline 批量 INCR / INCRBY）。
 * <p>
 * <b>业务背景</b>：一次首页点击会同时触发：
 * - 商品浏览数 +1
 * - 店铺浏览数 +1
 * - 用户当日访问数 +1
 * - 活动点击数 +1
 * - 搜索词计数 +1
 * <p>
 * 用循环 INCR 是 5 次 RTT；用 Pipeline 是 1 次 RTT。
 * <p>
 * <b>必须强调（防止误用）</b>：
 * 1) 单条 INCR 是原子的（Redis 单线程语义保证）；
 * 2) 但 Pipeline 中"5 条 INCR 作为一个整体"不是原子的；
 * 3) 中间可能被其他客户端命令插队执行；
 * 4) 这里用 Pipeline 不是为了一致性，仅为了减 RTT。
 * 5) 计数最终事实源不在 Redis，要异步落 DB / OLAP / MQ。
 */
public class L407UserBehaviorCounterPipelineScenario {

    private final StringRedisTemplate template;

    public L407UserBehaviorCounterPipelineScenario(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * 给一组 counterKey 各 +1。返回 List 顺序 == counterKeys 顺序，元素是 INCR 后的最新值（Long）。
     */
    public List<Object> increaseCounters(List<String> counterKeys) {
        return L407Pipelines.run(template, ops -> {
            for (String key : counterKeys) {
                ops.opsForValue().increment(key);
            }
            return null;
        });
    }

    /**
     * 按指定增量批量 INCRBY。Map 的迭代顺序对应结果顺序，建议传入 LinkedHashMap。
     */
    public Map<String, Long> increaseCountersWithDelta(Map<String, Long> counterDeltaMap) {
        List<String> orderedKeys = new java.util.ArrayList<>(counterDeltaMap.keySet());
        List<Object> results = L407Pipelines.run(template, ops -> {
            for (String key : orderedKeys) {
                ops.opsForValue().increment(key, counterDeltaMap.get(key));
            }
            return null;
        });

        Map<String, Long> map = new LinkedHashMap<>();
        for (int i = 0; i < orderedKeys.size(); i++) {
            Object o = results.get(i);
            map.put(orderedKeys.get(i), o == null ? 0L : ((Number) o).longValue());
        }
        return map;
    }

    /**
     * 批量读取计数器。注意 GET 出来是 String，业务自己转 Long。
     */
    public Map<String, Long> getCounters(List<String> counterKeys) {
        List<Object> results = L407Pipelines.run(template, ops -> {
            for (String key : counterKeys) {
                ops.opsForValue().get(key);
            }
            return null;
        });
        Map<String, Long> map = new LinkedHashMap<>();
        for (int i = 0; i < counterKeys.size(); i++) {
            Object o = results.get(i);
            map.put(counterKeys.get(i), o == null ? 0L : Long.parseLong(o.toString()));
        }
        return map;
    }

    /**
     * 清理一组计数器（用于实验后清场）。
     */
    public void clearCounters(List<String> counterKeys) {
        L407Pipelines.run(template, ops -> {
            for (String key : counterKeys) {
                ops.delete(key);
            }
            return null;
        });
    }
}
