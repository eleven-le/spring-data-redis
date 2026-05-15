package org.springframework.data.redis.laboratory.l4.l4_07.pipeline;

import org.springframework.data.redis.laboratory.l4.l4_07.pipeline.L407Pipelines;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_07.L407Keys;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * L4-07 Pipeline 基础实验。
 * <p>
 * 每个方法都是一个独立可调用的实验单元，都聚焦于：
 * <p> 1) 对应哪条 / 哪几条 Redis 命令； </p>
 * <p> 2) 对应 Spring Data Redis 的哪个 API；</p>
 * <p>3) 推荐断点位置；
 * <p>4) 返回值顺序如何理解；
 * <p> 5) 新手最容易踩什么坑。
 * <p>
 * 强调：Pipeline 的核心价值是减少 RTT，而不是让单条命令更快，更不是事务。
 */
public class L407PipelineBasicOperationsLab {

    private final StringRedisTemplate template;

    public L407PipelineBasicOperationsLab(StringRedisTemplate template) {
        this.template = template;
    }

    // ========================================================================
    // 一、SessionCallback 流派 —— 业务开发更友好
    // ========================================================================

    /**
     * 批量 GET（SessionCallback）。
     * <ul>
     *   <li>命令：N 个 GET</li>
     *   <li>API：RedisTemplate#executePipelined(SessionCallback)</li>
     *   <li>断点：RedisTemplate#executePipelined(SessionCallback)、RedisTemplate#execute、
     *           DefaultValueOperations#get、LettuceConnection#closePipeline、
     *           RedisTemplate#deserializeMixedResults</li>
     * </ul>
     * 返回 List 顺序 == keys 顺序；不存在的 key 对应位置返回 null。
     */
    public List<Object> pipelineBatchGetWithSessionCallback(List<String> keys) {
        // L407Pipelines.run 内部用匿名内部类实现 SessionCallback#execute（泛型方法 lambda 不能写）。
        // 业务侧只关心：在 ops 上发命令；真正的结果由 closePipeline 一次性吐回。
        return L407Pipelines.run(template, ops -> {
            for (String key : keys) {
                ops.opsForValue().get(key);
            }
            return null;
        });
    }

    /**
     * 批量 SET with TTL（SessionCallback）。
     * <p>
     * 优先用 set(key,value,duration) 而不是 set + expire 两步：
     * 1) 减少一半命令量；
     * 2) 避免 set 成功 expire 失败留下"永久不过期"脏 key。
     */
    public List<Object> pipelineBatchSetWithSessionCallback(List<String> keys,
                                                            List<String> values,
                                                            Duration ttl) {
        if (keys.size() != values.size()) {
            throw new IllegalArgumentException("keys 和 values 长度必须一致");
        }
        return L407Pipelines.run(template, ops -> {
            for (int i = 0; i < keys.size(); i++) {
                ops.opsForValue().set(keys.get(i), values.get(i), ttl);
            }
            return null;
        });
    }

    /**
     * 批量 DEL（SessionCallback）。
     */
    public List<Object> pipelineBatchDeleteWithSessionCallback(List<String> keys) {
        return L407Pipelines.run(template, ops -> {
            for (String key : keys) {
                ops.delete(key);
            }
            return null;
        });
    }

    /**
     * 批量 EXPIRE。
     */
    public List<Object> pipelineBatchExpireWithSessionCallback(List<String> keys, Duration ttl) {
        return L407Pipelines.run(template, ops -> {
            for (String key : keys) {
                ops.expire(key, ttl);
            }
            return null;
        });
    }

    /**
     * 混合命令 Pipeline。
     * <ul>
     *   <li>对同一 userKey：SET + EXPIRE + INCR + GET</li>
     *   <li>说明 Pipeline 不要求所有命令同类型 / 同 key</li>
     *   <li>但中间不要假定原子！其他客户端命令可能被 Redis 服务端插队执行</li>
     * </ul>
     */
    public List<Object> pipelineMixedCommandsWithSessionCallback(String userKey,
                                                                 String visitKey) {
        return L407Pipelines.run(template, ops -> {
            ops.opsForValue().set(userKey, "leiyuhang");
            ops.expire(userKey, 60, TimeUnit.SECONDS);
            ops.opsForValue().increment(visitKey);
            ops.opsForValue().get(userKey);
            return null;
        });
    }

    // ========================================================================
    // 二、RedisCallback 流派 —— 贴近底层连接，走读源码首选
    // ========================================================================

    /**
     * 批量 GET（RedisCallback，走 RedisConnection）。
     * <ul>
     *   <li>断点：LettuceConnection#stringCommands、LettuceStringCommands#get、
     *           LettuceConnection#closePipeline</li>
     *   <li>注意：RedisCallback 内部直接拿到 byte[]，必须自己用 keySerializer 转 byte[]</li>
     *   <li>Spring Data Redis 不会给你做 key 序列化，因为这个层级它假设你"懂自己在做什么"</li>
     * </ul>
     */
    public List<Object> pipelineBatchGetWithRedisCallback(List<String> keys) {
        @SuppressWarnings("unchecked")
        RedisSerializer<String> keySer = (RedisSerializer<String>) template.getKeySerializer();

        return template.executePipelined((RedisCallback<Object>) connection -> {
            for (String key : keys) {
                byte[] rawKey = keySer.serialize(key);
                connection.stringCommands().get(rawKey);
            }
            // 必须返回 null。返回非 null 不会成为 pipeline 结果，但会导致 IllegalStateException。
            return null;
        });
    }

    /**
     * 演示：直接用原始 byte[] API 写入 Pipeline。
     * <p>
     * 这是最贴近源码的写法。它告诉你：
     * 1) Pipeline 真正写到 socket 的就是 byte[]；
     * 2) Spring Data Redis 上层 ValueOperations / Operations 只是把 byte[] 转换流程"隐藏起来"；
     * 3) 想读懂 LettuceConnection / JedisConnection 源码，必须能盯住 byte[]。
     */
    public void pipelineRawByteArrayExample(List<String> keys, List<String> values) {
        if (keys.size() != values.size()) {
            throw new IllegalArgumentException("keys 和 values 长度必须一致");
        }
        template.executePipelined((RedisCallback<Object>) connection -> {
            for (int i = 0; i < keys.size(); i++) {
                byte[] k = keys.get(i).getBytes(StandardCharsets.UTF_8);
                byte[] v = values.get(i).getBytes(StandardCharsets.UTF_8);
                // 直接命中 LettuceStringCommands#set
                connection.stringCommands().set(k, v);
            }
            return null;
        });
    }

    // ========================================================================
    // 三、对照实验：普通循环 vs Pipeline
    // ========================================================================

    /**
     * 同样查 N 个 key，分别测两种方式耗时；返回 [normalCostMs, pipelineCostMs]。
     * <p>
     * 真实机器测下来：在 RTT 1ms 网络下，1000 个 GET：
     * - 普通循环 ≈ 1000ms（瓶颈完全是 RTT，不是 Redis）
     * - Pipeline ≈ 5～30ms（一次往返 + 一次性回收）
     * <p>
     * 注意：阿里云跨区或公网测试 RTT 更大，差距更明显。
     */
    public long[] compareNormalLoopVsPipeline(List<String> keys) {
        long t1 = System.currentTimeMillis();
        for (String key : keys) {
            template.opsForValue().get(key);
        }
        long normalCost = System.currentTimeMillis() - t1;

        long t2 = System.currentTimeMillis();
        pipelineBatchGetWithSessionCallback(keys);
        long pipelineCost = System.currentTimeMillis() - t2;

        return new long[]{normalCost, pipelineCost};
    }

    // ========================================================================
    // 四、辅助：准备一批 demo 数据（独立方法，方便 main 调用）
    // ========================================================================

    /**
     * 准备 0..count-1 共 count 个 demo key/value：
     * key: l4:07:pipeline:lab:{idx}
     * value: pipe-val-{idx}
     */
    public List<String> prepareDemoData(int count, Duration ttl) {
        List<String> keys = new ArrayList<>(count);
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add(L407Keys.labKey(i));
            values.add("pipe-val-" + i);
        }
        List<Object> objects = pipelineBatchSetWithSessionCallback(keys, values, ttl);
        return keys;
    }

    /**
     * 清理 0..count-1 demo key。
     */
    public void cleanupDemoData(int count) {
        List<String> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add(L407Keys.labKey(i));
        }
        pipelineBatchDeleteWithSessionCallback(keys);
    }

    /**
     * 工具方法：构造 [from, to) 区间的 demo key。
     */
    public static List<String> demoKeyRange(int from, int to) {
        List<String> keys = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            keys.add(L407Keys.labKey(i));
        }
        return keys;
    }

    /**
     * 工具方法：演示用 StringRedisSerializer。
     */
    public static StringRedisSerializer stringSerializer() {
        return StringRedisSerializer.UTF_8;
    }

    /**
     * 工具方法：list 的"安全 toString"，避免大数据 demo 输出炸屏。
     */
    public static String previewList(List<?> list, int max) {
        if (list == null) {
            return "null";
        }
        if (list.size() <= max) {
            return list.toString();
        }
        return list.subList(0, max) + "... (total=" + list.size() + ")";
    }

    /**
     * 仅做一个 raw byte[] preview：把前几条 GET 结果原样转成 String 给人眼看。
     * <p>
     * 当 RedisCallback 模式下结果是 byte[] 时（而非已被反序列化的 String），可以用它确认。
     */
    public static String previewByteArrayResults(List<Object> results, int max) {
        List<Object> view = new ArrayList<>(Math.min(results.size(), max));
        for (int i = 0; i < Math.min(results.size(), max); i++) {
            Object o = results.get(i);
            if (o instanceof byte[]) {
                view.add(new String((byte[]) o, StandardCharsets.UTF_8));
            } else {
                view.add(o);
            }
        }
        return view + (results.size() > max ? "... (total=" + results.size() + ")" : "");
    }

    /**
     * 兼容旧 toString 输出：避免某些场景误把 Arrays 误当 String[]。
     */
    public static String safeToString(Object[] arr) {
        return Arrays.toString(arr);
    }
}
