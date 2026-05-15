package org.springframework.data.redis.laboratory.l4.l4_09.compare;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 对比：ZSCAN vs ZRANGE / ZREVRANGE。
 * <p>
 * 一句话决策：
 * <ul>
 *   <li>排行榜分页展示给用户 → 必须 ZREVRANGE / ZRANGE / ZRANGEBYSCORE，<b>禁止</b> ZSCAN；</li>
 *   <li>后台扫描全部 member 做修复 / 迁移 / 校验 → 用 ZSCAN。</li>
 * </ul>
 */
public class L409ScanVsRangeCompare {

    private final StringRedisTemplate template;
    private final ZSetOperations<String, String> zsetOps;

    public L409ScanVsRangeCompare(StringRedisTemplate template) {
        this.template = template;
        this.zsetOps = template.opsForZSet();
    }

    /**
     * 后台检查用 ZSCAN：全量遍历，不要求 score 顺序，不阻塞主线程。
     */
    public List<ZSetOperations.TypedTuple<String>> zscanForBackgroundCheck(String key, int count, int max) {
        ScanOptions options = ScanOptions.scanOptions().count(count).build();
        List<ZSetOperations.TypedTuple<String>> result = new ArrayList<>();
        try (Cursor<ZSetOperations.TypedTuple<String>> cursor = zsetOps.scan(key, options)) {
            while (cursor.hasNext() && result.size() < max) {
                result.add(cursor.next());
            }
        }
        return result;
    }

    /**
     * 业务展示用 ZREVRANGE：稳定 score 倒排，分页友好，O(log(N)+M)。
     */
    public Set<ZSetOperations.TypedTuple<String>> zrangeForRankingPage(String key, long page, long pageSize) {
        long start = page * pageSize;
        long end = start + pageSize - 1;
        return zsetOps.reverseRangeWithScores(key, start, end);
    }
}
