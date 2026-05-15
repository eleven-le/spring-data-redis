package org.springframework.data.redis.laboratory.l4.l4_09.scenario;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_09.L409Keys;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 场景 7：Scan + Pipeline 组合。
 * <p>
 * <b>分工</b>：Scan 负责"找一批 key"，Pipeline 负责"对这批 key 一次 RTT 执行批量命令"。
 * 两者解决不同问题，组合使用是后台任务的标配：
 * <ul>
 *   <li>Scan 不会一次返回全部，避免主线程长时间阻塞；</li>
 *   <li>Pipeline 把"对每个 key 做 EXPIRE/DEL/TTL"的 N 次 RTT 压成 1 次。</li>
 * </ul>
 * <p>
 * 关键约束：
 * <ol>
 *   <li>不要把所有 scan 结果攒成一个超大 List 再 pipeline——分批；</li>
 *   <li>单批 pipeline 大小 100~1000，过大触发客户端 buffer / Redis 输出 buffer 风险；</li>
 *   <li>失败 key 单独记录，重跑幂等。</li>
 * </ol>
 */
public class L409ScanPipelineBatchProcessScenario {

    private final StringRedisTemplate template;

    public L409ScanPipelineBatchProcessScenario(StringRedisTemplate template) {
        this.template = template;
    }

    /** 准备一批 demo 数据。 */
    public void prepareDemoData(int n) {
        for (int i = 0; i < n; i++) {
            template.opsForValue().set(L409Keys.PIPELINE_BATCH_PREFIX + i, "v-" + i);
        }
    }

    /** Scan + Pipeline EXPIRE：给一批 key 统一打 TTL。返回已处理 key 数。 */
    public long scanAndExpire(String pattern, Duration ttl, int scanCount, int batchSize) {
        return scanAndPipeline(pattern, scanCount, batchSize, (conn, batch) -> {
            byte[][] rawKeys = toBytes(batch);
            long ms = ttl.toMillis();
            for (byte[] k : rawKeys) {
                conn.pExpire(k, ms);
            }
        });
    }

    /** Scan + Pipeline DEL：批量删除（小 key 友好；大 key 用 UNLINK）。 */
    public long scanAndDelete(String pattern, int scanCount, int batchSize) {
        return scanAndPipeline(pattern, scanCount, batchSize, (conn, batch) -> {
            byte[][] rawKeys = toBytes(batch);
            for (byte[] k : rawKeys) {
                conn.del(k);
            }
        });
    }

    /** Scan + Pipeline TTL 检查：拉所有 key 的剩余 TTL，便于排查过期策略。 */
    public long scanAndCheckTtl(String pattern, int scanCount, int batchSize) {
        return scanAndPipeline(pattern, scanCount, batchSize, (conn, batch) -> {
            byte[][] rawKeys = toBytes(batch);
            for (byte[] k : rawKeys) {
                conn.ttl(k);
            }
        });
    }

    public long clearDemoData() {
        return scanAndDelete(L409Keys.PIPELINE_BATCH_PREFIX + "*", 500, 200);
    }

    private long scanAndPipeline(String pattern, int scanCount, int batchSize, BatchOp op) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(scanCount).build();
        long processed = 0;
        List<String> buffer = new ArrayList<>(batchSize);
        try (Cursor<String> cursor = template.scan(options)) {
            while (cursor.hasNext()) {
                buffer.add(cursor.next());
                if (buffer.size() >= batchSize) {
                    pipelineBatch(buffer, op);
                    processed += buffer.size();
                    buffer.clear();
                }
            }
            if (!buffer.isEmpty()) {
                pipelineBatch(buffer, op);
                processed += buffer.size();
            }
        }
        return processed;
    }

    private void pipelineBatch(List<String> keys, BatchOp op) {
        List<String> snapshot = new ArrayList<>(keys);
        // executePipelined：拿独立连接 → openPipeline → callback → closePipeline → 反序列化
        template.executePipelined((RedisCallback<Object>) connection -> {
            op.execute(connection, snapshot);
            return null; // pipeline callback 必须返回 null
        });
    }

    private static byte[][] toBytes(List<String> keys) {
        byte[][] arr = new byte[keys.size()][];
        for (int i = 0; i < keys.size(); i++) {
            arr[i] = keys.get(i).getBytes(StandardCharsets.UTF_8);
        }
        return arr;
    }

    @FunctionalInterface
    private interface BatchOp {
        void execute(RedisConnection conn, List<String> batch);
    }
}
