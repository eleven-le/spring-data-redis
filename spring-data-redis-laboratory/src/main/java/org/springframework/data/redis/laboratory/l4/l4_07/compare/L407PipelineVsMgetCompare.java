package org.springframework.data.redis.laboratory.l4.l4_07.compare;

import org.springframework.data.redis.laboratory.l4.l4_07.pipeline.L407Pipelines;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Pipeline vs MGET 对比。
 * <p>
 * 结论先行：
 *   - 同前缀同类型的"纯批量 GET"——优先用 MGET（一条命令、一次 RTT、最小开销）；
 *   - 跨数据结构 / 混合命令 / 同时还要 EXPIRE/SET——上 Pipeline；
 *   - 需要原子复合逻辑——上 Lua（不在本类讨论）。
 * <p>
 * 不要靠想象拍板，所有"哪种快"都要靠压测；本类提供最小可执行对照。
 */
public class L407PipelineVsMgetCompare {

    private final StringRedisTemplate template;

    public L407PipelineVsMgetCompare(StringRedisTemplate template) {
        this.template = template;
    }

    public void prepareDemo(int count) {
        List<String> keys = buildKeys(count);
        L407Pipelines.run(template, ops -> {
            for (String k : keys) {
                ops.opsForValue().set(k, "v-" + k, Duration.ofMinutes(10));
            }
            return null;
        });
    }

    public long normalLoopGet(int count) {
        List<String> keys = buildKeys(count);
        long t1 = System.currentTimeMillis();
        for (String k : keys) {
            template.opsForValue().get(k);
        }
        return System.currentTimeMillis() - t1;
    }

    public long mget(int count) {
        List<String> keys = buildKeys(count);
        long t1 = System.currentTimeMillis();
        template.opsForValue().multiGet(keys);
        return System.currentTimeMillis() - t1;
    }

    public long pipelineGet(int count) {
        List<String> keys = buildKeys(count);
        long t1 = System.currentTimeMillis();
        L407Pipelines.run(template, ops -> {
            for (String k : keys) {
                ops.opsForValue().get(k);
            }
            return null;
        });
        return System.currentTimeMillis() - t1;
    }

    private List<String> buildKeys(int count) {
        List<String> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add("l4:07:compare:mget:" + i);
        }
        return keys;
    }
}
