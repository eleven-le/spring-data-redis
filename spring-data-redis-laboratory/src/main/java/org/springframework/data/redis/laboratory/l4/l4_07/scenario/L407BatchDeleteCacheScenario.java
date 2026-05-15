package org.springframework.data.redis.laboratory.l4.l4_07.scenario;

import org.springframework.data.redis.laboratory.l4.l4_07.pipeline.L407Pipelines;

import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_07.L407Keys;
import org.springframework.data.redis.laboratory.l4.l4_07.pipeline.BatchMetrics;
import org.springframework.data.redis.laboratory.l4.l4_07.pipeline.L407PipelineBatchExecutor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 场景 6.5：大批量删除缓存。
 * <p>
 * <b>业务背景</b>：商品批量下架、活动结束、配置切换，需要瞬间清掉 N 万个 key。
 * <p>
 * <b>三种方案</b>：
 * 1) {@code redisTemplate.delete(Collection)} —— SDR 内部最终发的是 DEL k1 k2 ... 多 key 命令；
 * 但参数过多会打满网络包，集群模式下还要按 slot 分组，复杂度高；
 * 2) Pipeline 分批 DEL —— 控制每批数量，统计耗时；
 * 3) UNLINK —— 异步释放内存，不阻塞 Redis 单线程；适合大 key / 海量 key。
 * <p>
 * 删除"大 key"是经典事故源。生产建议：
 * - 先 SCAN 探明 key 大小；
 * - 大 zset / hash / set 用 HSCAN / SSCAN / ZSCAN 分片删；
 * - DEL 大 key 用 UNLINK 替代；
 * - 删除节奏限速（每批后 sleep 几 ms）。
 */
public class L407BatchDeleteCacheScenario {

    private final StringRedisTemplate template;

    public L407BatchDeleteCacheScenario(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * 准备 demo 数据。
     */
    public void prepareDemoKeys(int count) {
        L407Pipelines.run(template, ops -> {
            for (int i = 0; i < count; i++) {
                ops.opsForValue().set(L407Keys.deleteDemo(i), "v-" + i, Duration.ofMinutes(10));
            }
            return null;
        });
    }

    /**
     * 方案 1：Pipeline 分批 DEL。
     */
    public BatchMetrics deleteByPipeline(List<String> keys, int batchSize) {
        L407PipelineBatchExecutor<String, List<Object>> executor =
                new L407PipelineBatchExecutor<>(batchSize);
        return executor.executeInBatchesWithMetrics(keys, batch ->
                L407Pipelines.run(template, ops -> {
                    for (String key : batch) {
                        ops.delete(key);
                    }
                    return null;
                })
        );
    }

    /**
     * 方案 2：使用 UNLINK 异步释放（如果 Redis &gt;= 4.0 支持）。
     * <p>
     * Spring Data Redis 提供 {@link RedisOperations#unlink(java.util.Collection)}，
     * 这里用 RedisCallback 演示如何走到底层 keyCommands().unlink。
     * <p>
     * UNLINK 的语义：把 key 从 keyspace 立即解绑，内存释放交给后台线程。
     * 对大 key 删除来说，UNLINK 几乎不占主线程。
     */
    public BatchMetrics deleteByUnlinkIfSupported(List<String> keys, int batchSize) {
        L407PipelineBatchExecutor<String, List<Object>> executor =
                new L407PipelineBatchExecutor<>(batchSize);
        return executor.executeInBatchesWithMetrics(keys, batch ->
                template.executePipelined((RedisCallback<Object>) connection -> {
                    for (String key : batch) {
                        byte[] raw = key.getBytes(StandardCharsets.UTF_8);
                        connection.keyCommands().unlink(raw);
                    }
                    return null;
                })
        );
    }

    /**
     * 方案 3：RedisTemplate.delete(Collection) —— 内部走 DEL k1 k2 ...
     * 注意：参数过多 Lettuce 会自动分批；阅读 LettuceConnection#del 可以看到。
     */
    public long deleteByRedisTemplateDelete(List<String> keys) {
        Long deleted = template.delete(keys);
        return deleted == null ? 0L : deleted;
    }

    /**
     * 仅用于 demo 输出对比。
     */
    public CompareDeleteWaysResult compareDeleteWays(int count, int batchSize) {
        // 1. Pipeline DEL
        prepareDemoKeys(count);
        List<String> keys = buildKeys(count);
        long t1 = System.currentTimeMillis();
        BatchMetrics m1 = deleteByPipeline(keys, batchSize);
        long pipelineCost = System.currentTimeMillis() - t1;

        // 2. UNLINK
        prepareDemoKeys(count);
        long t2 = System.currentTimeMillis();
        BatchMetrics m2 = deleteByUnlinkIfSupported(keys, batchSize);
        long unlinkCost = System.currentTimeMillis() - t2;

        // 3. RedisTemplate.delete(Collection)
        prepareDemoKeys(count);
        long t3 = System.currentTimeMillis();
        long deleted = deleteByRedisTemplateDelete(keys);
        long templateCost = System.currentTimeMillis() - t3;

        return new CompareDeleteWaysResult(pipelineCost, unlinkCost, templateCost, deleted, m1, m2);
    }

    private List<String> buildKeys(int count) {
        List<String> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add(L407Keys.deleteDemo(i));
        }
        return keys;
    }

    public record CompareDeleteWaysResult(long pipelineCostMs,
                                          long unlinkCostMs,
                                          long templateDeleteCostMs,
                                          long templateDeleteReturned,
                                          BatchMetrics pipelineMetrics,
                                          BatchMetrics unlinkMetrics) {
    }

    /**
     * 仅 demo 用：检查 Redis 是否支持 UNLINK（4.0+）。生产中请直接判断 Redis 版本，不要靠 try/catch 探测。
     */
    public boolean redisSupportsUnlink() {
        try {
            RedisServerCommands cmd = template.getRequiredConnectionFactory()
                    .getConnection().serverCommands();
            String info = String.valueOf(cmd.info("server"));
            return info != null && info.contains("redis_version");
        } catch (Exception e) {
            return true;
        }
    }
}
