package org.springframework.data.redis.laboratory.l4.l4_09.scenario;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.laboratory.l4.l4_09.L409Keys;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 场景 6：大 ZSet 增量检查 / 修复。
 * <p>
 * <b>业务故事</b>：内容热榜 {@code l4:09:zset:rank:hot:{yyyyMMdd}}，运维需要：
 * <ul>
 *   <li>展示榜单前 50（业务展示）—— 用 ZREVRANGE，<b>不要用 ZSCAN</b>；</li>
 *   <li>检查所有 score &lt;= 0 的脏 member 并清理（后台任务）—— 用 ZSCAN 全量遍历。</li>
 * </ul>
 * 这是 ZSCAN 的本职：后台扫描、修复、迁移；它不是分页 API。
 */
public class L409LargeZSetCheckScenario {

    private final StringRedisTemplate template;
    private final ZSetOperations<String, String> zsetOps;

    public L409LargeZSetCheckScenario(StringRedisTemplate template) {
        this.template = template;
        this.zsetOps = template.opsForZSet();
    }

    public String rankKey(String date) {
        return L409Keys.ZSET_DEMO_KEY + ":" + date;
    }

    public void prepareRankData(String date, int memberCount) {
        String key = rankKey(date);
        for (int i = 0; i < memberCount; i++) {
            // 故意写入一些 score=0 的脏数据，便于演示扫描修复
            double score = (i % 23 == 0) ? 0d : (memberCount - i);
            zsetOps.add(key, "content-" + i, score);
        }
    }

    /** ZSCAN 后台检查：报告 score<=0 的脏 member。返回脏 member 数。 */
    public long checkRankDataByZScan(String date, int scanCount, int batchSize) {
        String key = rankKey(date);
        ScanOptions options = ScanOptions.scanOptions().count(scanCount).build();
        long dirty = 0;
        List<String> dirtyBuffer = new ArrayList<>(batchSize);
        try (Cursor<ZSetOperations.TypedTuple<String>> cursor = zsetOps.scan(key, options)) {
            while (cursor.hasNext()) {
                ZSetOperations.TypedTuple<String> t = cursor.next();
                Double s = t.getScore();
                if (s != null && s <= 0d) {
                    dirtyBuffer.add(t.getValue());
                    dirty++;
                    if (dirtyBuffer.size() >= batchSize) {
                        // 这里只汇报，不删除（演示场景）。生产会做 ZREM 清理。
                        reportDirty(dirtyBuffer);
                        dirtyBuffer.clear();
                    }
                }
            }
            if (!dirtyBuffer.isEmpty()) reportDirty(dirtyBuffer);
        }
        return dirty;
    }

    /** 业务展示走 ZREVRANGE：稳定、可分页。 */
    public Set<ZSetOperations.TypedTuple<String>> getTopNByRange(String date, long topN) {
        return zsetOps.reverseRangeWithScores(rankKey(date), 0, topN - 1);
    }

    public void cleanup(String date) {
        template.delete(rankKey(date));
    }

    private void reportDirty(List<String> dirty) {
        // 生产里写监控 / 工单 / 慢任务汇报
    }
}
