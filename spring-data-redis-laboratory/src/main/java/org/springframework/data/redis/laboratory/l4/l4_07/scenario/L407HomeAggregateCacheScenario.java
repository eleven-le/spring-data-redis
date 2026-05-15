package org.springframework.data.redis.laboratory.l4.l4_07.scenario;

import org.springframework.data.redis.laboratory.l4.l4_07.pipeline.L407Pipelines;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 场景 6.1：C 端首页聚合接口批量查缓存。
 * <p>
 * <b>业务背景</b>：茶饮 C 端首页 = 商品模块 + Banner + 优惠券 + 配置。
 * 一个首页接口要查 30 个 key（不同前缀、不同模块），早期写法是循环 GET，
 * 后来发现 P95 接口耗时被网络 RTT 拖到 200ms+。
 * <p>
 * <b>三种方案对照</b>： <p>
 * 1) 普通循环 GET：N 次 RTT；<p>
 * 2) MGET：一条 MGET 命令一次 RTT，但只能查同一种"字符串"类型；<p>
 * 3) Pipeline GET：一次 RTT，但能混合多种命令（GET / HGET / ZRANGE...）。<p>
 * <p>
 * 经验：<p>
 * - 同前缀同类型的批量 GET：MGET 更直白； <p>
 * - 跨数据结构 / 混合命令：Pipeline； <p>
 * - C 端首页这种"先批量 GET 再做 fallback 回源"的接口，Pipeline 更合适，
 * 因为 fallback 还要写 EXPIRE / SET / HSET，混在一起更划算。
 */
public class L407HomeAggregateCacheScenario {

    private final StringRedisTemplate template;

    public L407HomeAggregateCacheScenario(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * 准备 demo 首页数据：往 Redis 写入若干 home:* key。
     */
    public void prepareDemoData(List<String> moduleKeys) {
        L407Pipelines.run(template, ops -> {
            for (String key : moduleKeys) {
                ops.opsForValue().set(key, "value-of-" + key, Duration.ofMinutes(10));
            }
            return null;
        });
    }

    /**
     * 方案 1：普通循环 GET。仅做对照。
     */
    public Map<String, String> getHomeModulesByNormalLoop(List<String> moduleKeys) {
        Map<String, String> map = new LinkedHashMap<>(moduleKeys.size() * 2);
        for (String key : moduleKeys) {
            map.put(key, template.opsForValue().get(key));
        }
        return map;
    }

    /**
     * 方案 2：MGET。
     * <p>
     * 命令变成一条 MGET k1 k2 k3...，一次 RTT。
     * 不存在的 key 在返回 List 中是 null。
     */
    public Map<String, String> getHomeModulesByMget(List<String> moduleKeys) {
        List<String> values = template.opsForValue().multiGet(moduleKeys);
        Map<String, String> map = new LinkedHashMap<>(moduleKeys.size() * 2);
        for (int i = 0; i < moduleKeys.size(); i++) {
            map.put(moduleKeys.get(i), values == null ? null : values.get(i));
        }
        return map;
    }

    /**
     * 方案 3：Pipeline GET。
     * <p>
     * 它的真正价值不在"和 MGET 比快"，而在：
     * 1) 可以混合 GET / HGET / ZRANGE；
     * 2) 可以在同一 Pipeline 里给某些 key 续期（EXPIRE）；
     * 3) 可以用 SessionCallback 直接写 ops.opsForValue().get / opsForHash().entries / opsForZSet().reverseRangeWithScores。
     */
    public Map<String, String> getHomeModulesByPipeline(List<String> moduleKeys) {
        List<Object> results = L407Pipelines.run(template, ops -> {
            for (String key : moduleKeys) {
                ops.opsForValue().get(key);
            }
            return null;
        });
        Map<String, String> map = new LinkedHashMap<>(moduleKeys.size() * 2);
        for (int i = 0; i < moduleKeys.size(); i++) {
            Object raw = results.get(i);
            map.put(moduleKeys.get(i), raw == null ? null : raw.toString());
        }
        return map;
    }

    /**
     * 三种方案耗时对比。仅用于本地体感对比，不能当作严肃压测。
     * 真实压测：固定 batchSize、固定连接、warmup 再统计。
     */
    public CompareResult compareThreeWays(List<String> moduleKeys) {
        // warmup
        getHomeModulesByPipeline(moduleKeys);

        long t1 = System.currentTimeMillis();
        getHomeModulesByNormalLoop(moduleKeys);
        long normal = System.currentTimeMillis() - t1;

        long t2 = System.currentTimeMillis();
        getHomeModulesByMget(moduleKeys);
        long mget = System.currentTimeMillis() - t2;

        long t3 = System.currentTimeMillis();
        getHomeModulesByPipeline(moduleKeys);
        long pipeline = System.currentTimeMillis() - t3;

        return new CompareResult(moduleKeys.size(), normal, mget, pipeline);
    }

    /**
     * 工具：构造 demo 首页 key 集合。
     */
    public static List<String> buildDemoHomeKeys() {
        List<String> keys = new ArrayList<>(40);
        for (int i = 0; i < 10; i++) {
            keys.add("l4:07:home:product:" + i);
            keys.add("l4:07:home:banner:" + i);
            keys.add("l4:07:home:coupon:" + i);
            keys.add("l4:07:home:config:" + i);
        }
        return keys;
    }

    public record CompareResult(int total, long normalCost, long mgetCost, long pipelineCost) {
        @Override
        public String toString() {
            return "Compare{total=" + total
                    + ", normal=" + normalCost + "ms"
                    + ", mget=" + mgetCost + "ms"
                    + ", pipeline=" + pipelineCost + "ms}";
        }
    }
}
