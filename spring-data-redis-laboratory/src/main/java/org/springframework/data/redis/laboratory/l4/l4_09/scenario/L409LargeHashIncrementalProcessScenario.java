package org.springframework.data.redis.laboratory.l4.l4_09.scenario;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_09.L409Keys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 场景 4：大 Hash 增量遍历处理。
 * <p>
 * <b>业务故事</b>：用户行为 Hash {@code l4:09:hash:user:behavior:{uid}}——
 * field 形如 {@code view:{itemId}}、{@code like:{itemId}}、{@code fav:{itemId}}，
 * 重度用户半年累计 30w field。一次 entries() 拉全量，不仅客户端 OOM 风险高，
 * Redis 主线程也要花几十毫秒序列化整个 Hash。HSCAN 是当前止血方案；
 * <b>真正的根治是在业务层把这个大 Hash 拆成多个 sub-key（按月/按行为类型分片）</b>。
 */
public class L409LargeHashIncrementalProcessScenario {

    private final StringRedisTemplate template;
    private final HashOperations<String, String, String> hashOps;

    public L409LargeHashIncrementalProcessScenario(StringRedisTemplate template) {
        this.template = template;
        this.hashOps = template.opsForHash();
    }

    public String userKey(String userId) {
        return L409Keys.HASH_DEMO_KEY + ":" + userId;
    }

    public void prepareUserBehavior(String userId, int itemCount) {
        String key = userKey(userId);
        Map<String, String> bulk = new HashMap<>();
        for (int i = 0; i < itemCount; i++) {
            String fieldPrefix = (i % 3 == 0) ? "view:" : (i % 3 == 1) ? "like:" : "fav:";
            bulk.put(fieldPrefix + i, String.valueOf(i));
            if (bulk.size() >= 1000) {
                hashOps.putAll(key, bulk);
                bulk.clear();
            }
        }
        if (!bulk.isEmpty()) {
            hashOps.putAll(key, bulk);
        }
    }

    /**
     * HSCAN 增量处理。每批 batchSize 个 field 调用一次"模拟下游处理"。
     * 重要：buffer 复用、用完清空——不要把全量 field 攒在内存里。
     */
    public long processBehaviorByHScan(String userId, int scanCount, int batchSize) {
        String key = userKey(userId);
        ScanOptions options = ScanOptions.scanOptions().count(scanCount).build();
        long processed = 0;
        List<Map.Entry<String, String>> buffer = new ArrayList<>(batchSize);
        try (Cursor<Map.Entry<String, String>> cursor = hashOps.scan(key, options)) {
            while (cursor.hasNext()) {
                buffer.add(cursor.next());
                if (buffer.size() >= batchSize) {
                    handleBatch(buffer);
                    processed += buffer.size();
                    buffer.clear();
                }
            }
            if (!buffer.isEmpty()) {
                handleBatch(buffer);
                processed += buffer.size();
            }
        }
        return processed;
    }

    /**
     * 反例：直接 entries() 一次拉全量。请勿在生产对 30w field 的 Hash 这样写。
     */
    @Deprecated
    public Map<String, String> processBehaviorByEntriesDangerExample(String userId) {
        return hashOps.entries(userKey(userId));
    }

    public void cleanup(String userId) {
        template.delete(userKey(userId));
    }

    /**
     * 模拟下游处理：写离线 / 打 Kafka / 计算特征。这里仅做一次 size 统计。
     */
    private void handleBatch(List<Map.Entry<String, String>> batch) {
        // no-op：业务里替换成 Kafka producer / OLAP sink 即可
    }
}
