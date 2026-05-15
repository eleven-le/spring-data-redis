package org.springframework.data.redis.laboratory.l4.l4_09.zset;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.laboratory.l4.l4_09.L409Keys;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * L4-09 ZSCAN 实验：增量遍历 ZSet 的 member + score。
 * <p>
 * 业务：内容热榜 {@code l4:09:zset:rank:hot}。
 * <p>
 * <b>关键澄清</b>：ZSCAN <b>不是</b>分页 API，<b>不</b>适合做"排行榜第 N 页"业务展示。
 * 排行榜分页 → ZRANGE / ZREVRANGE / ZRANGEBYSCORE。
 * ZSCAN 适合后台扫描：检查、修复、迁移、批量更新。
 * <p>
 * 断点位置：
 * <ul>
 *   <li>{@code ZSetOperations.scan(K, ScanOptions)}</li>
 *   <li>{@code DefaultZSetOperations.scan}</li>
 *   <li>{@code RedisZSetCommands.zScan} / {@code LettuceZSetCommands.zScan}</li>
 *   <li>{@code Cursor<TypedTuple<V>>}</li>
 * </ul>
 */
public class L409ZSetScanLab {

    private final StringRedisTemplate template;
    private final ZSetOperations<String, String> zsetOps;

    public L409ZSetScanLab(StringRedisTemplate template) {
        this.template = template;
        this.zsetOps = template.opsForZSet();
    }

    public String defaultKey() {
        return L409Keys.ZSET_DEMO_KEY;
    }

    /**
     * 准备一个中等规模 ZSet 用于实验。
     */
    public void prepareLargeZSet(String key, int memberCount) {
        for (int i = 0; i < memberCount; i++) {
            zsetOps.add(key, "content-" + i, (memberCount - i));
        }
    }

    public List<ZSetOperations.TypedTuple<String>> zscanAll(String key, int count, int maxMembers) {
        return zscanWith(key, ScanOptions.scanOptions().count(count).build(), maxMembers);
    }

    public List<ZSetOperations.TypedTuple<String>> zscanByPattern(String key, String pattern, int count, int maxMembers) {
        return zscanWith(key, ScanOptions.scanOptions().match(pattern).count(count).build(), maxMembers);
    }

    /**
     * 分批处理（修复 / 迁移场景），不要全量收集。
     */
    public long zscanAndProcessInBatches(String key, int count, int batchSize,
                                         Consumer<List<ZSetOperations.TypedTuple<String>>> batchHandler) {
        ScanOptions options = ScanOptions.scanOptions().count(count).build();
        long processed = 0;
        List<ZSetOperations.TypedTuple<String>> buffer = new ArrayList<>(batchSize);
        try (Cursor<ZSetOperations.TypedTuple<String>> cursor = zsetOps.scan(key, options)) {
            while (cursor.hasNext()) {
                buffer.add(cursor.next());
                processed++;
                if (buffer.size() >= batchSize) {
                    batchHandler.accept(new ArrayList<>(buffer));
                    buffer.clear();
                }
            }
            if (!buffer.isEmpty()) {
                batchHandler.accept(buffer);
            }
        }
        return processed;
    }

    /**
     * 对照：业务展示榜单走 ZREVRANGE。
     * 这是稳定的 score 排序分页，N 个元素 O(log(N) + M)，是排行榜唯一正解。
     */
    public Set<ZSetOperations.TypedTuple<String>> compareRangeVsZScanConcept(String key, long topN) {
        return zsetOps.reverseRangeWithScores(key, 0, topN - 1);
    }

    public void cleanup(String key) {
        template.delete(key);
    }

    private List<ZSetOperations.TypedTuple<String>> zscanWith(String key, ScanOptions options, int maxMembers) {
        List<ZSetOperations.TypedTuple<String>> result = new ArrayList<>();
        try (Cursor<ZSetOperations.TypedTuple<String>> cursor = zsetOps.scan(key, options)) {
            while (cursor.hasNext() && result.size() < maxMembers) {
                result.add(cursor.next());
            }
        }
        return result;
    }
}
