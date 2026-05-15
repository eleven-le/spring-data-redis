package org.springframework.data.redis.laboratory.l4.l4_04.zset;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.laboratory.l4.l4_04.L404Keys;

import java.util.Set;

/**
 * Redis ZSet 命令全家桶实验。
 * <p>
 * 断点路径：
 * DefaultZSetOperations.xxx → RedisTemplate.execute(callback) →
 * RedisCallback.doInRedis → connection.zSetCommands().zAdd / zIncrBy / zRange ...
 * <p>
 * 易错提醒：
 * - rank/range 是按 score 升序的（小 → 大）；要榜单 Top N 用 reverseRange / reverseRank。
 * - score 是 double，不要用它存超过 2^53 的整型 ID（精度丢失）。
 */
public class L404ZSetBasicOperationsLab {

    private final StringRedisTemplate template;
    private final ZSetOperations<String, String> ops;

    public L404ZSetBasicOperationsLab(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForZSet();
    }

    /**
     * ZADD —— 加入或更新 score。
     */
    public void demoAdd() {
        String key = L404Keys.ZSET_LAB + "add";
        template.delete(key);
        ops.add(key, "珍珠奶茶", 100);
        ops.add(key, "椰果奶茶", 80);
        ops.add(key, "杨枝甘露", 120);
        System.out.println("[ZADD]   " + ops.rangeWithScores(key, 0, -1));
    }

    /**
     * ZREM —— 移除元素。
     */
    public void demoRemove() {
        String key = L404Keys.ZSET_LAB + "rem";
        template.delete(key);
        ops.add(key, "a", 1);
        ops.add(key, "b", 2);
        ops.remove(key, "a");
        System.out.println("[ZREM]   left=" + ops.range(key, 0, -1));
    }

    /**
     * ZSCORE —— 取某成员 score。
     */
    public void demoScore() {
        String key = L404Keys.ZSET_LAB + "score";
        template.delete(key);
        ops.add(key, "item-1", 88.5);
        System.out.println("[ZSCORE] item-1=" + ops.score(key, "item-1"));
    }

    /**
     * ZINCRBY —— 原子 score + delta。排行榜累加销量必备。
     */
    public void demoIncrementScore() {
        String key = L404Keys.ZSET_LAB + "incr";
        template.delete(key);
        ops.add(key, "item-1", 0);
        ops.incrementScore(key, "item-1", 5);
        ops.incrementScore(key, "item-1", 3);
        System.out.println("[ZINCRBY] item-1=" + ops.score(key, "item-1"));
        // 断点: DefaultZSetOperations.incrementScore → connection.zSetCommands().zIncrBy
    }

    /**
     * ZRANK —— 升序排名（score 小的排前面）。
     */
    public void demoRank() {
        String key = L404Keys.ZSET_LAB + "rank";
        template.delete(key);
        ops.add(key, "a", 10);
        ops.add(key, "b", 30);
        ops.add(key, "c", 20);
        System.out.println("[ZRANK]   a=" + ops.rank(key, "a")
                + " b=" + ops.rank(key, "b")
                + " c=" + ops.rank(key, "c"));
    }

    /**
     * ZREVRANK —— 降序排名（score 大的排前面，榜单需要的方向）。
     */
    public void demoReverseRank() {
        String key = L404Keys.ZSET_LAB + "revrank";
        template.delete(key);
        ops.add(key, "a", 10);
        ops.add(key, "b", 30);
        ops.add(key, "c", 20);
        System.out.println("[ZREVRANK] b=" + ops.reverseRank(key, "b") // b 是 score 最大 → rank 0
                + " a=" + ops.reverseRank(key, "a"));
    }

    /**
     * ZRANGE —— 按 score 升序取。
     */
    public void demoRange() {
        String key = L404Keys.ZSET_LAB + "range";
        template.delete(key);
        ops.add(key, "a", 1);
        ops.add(key, "b", 2);
        ops.add(key, "c", 3);
        System.out.println("[ZRANGE 0 -1] " + ops.range(key, 0, -1));
    }

    /**
     * ZREVRANGE —— 按 score 降序。Top N 榜单首选。
     */
    public void demoReverseRange() {
        String key = L404Keys.ZSET_LAB + "rev";
        template.delete(key);
        ops.add(key, "a", 1);
        ops.add(key, "b", 2);
        ops.add(key, "c", 3);
        System.out.println("[ZREVRANGE 0 1] " + ops.reverseRange(key, 0, 1));
    }

    /**
     * ZRANGE WITHSCORES —— 同时拿 score；返回 TypedTuple。
     */
    public void demoRangeWithScores() {
        String key = L404Keys.ZSET_LAB + "rws";
        template.delete(key);
        ops.add(key, "a", 1);
        ops.add(key, "b", 2);
        Set<TypedTuple<String>> tuples = ops.rangeWithScores(key, 0, -1);
        for (TypedTuple<String> t : tuples) {
            System.out.println("[ZRANGE WS] " + t.getValue() + " score=" + t.getScore());
        }
        // 断点: DefaultZSetOperations.rangeWithScores → connection.zRangeWithScores
        //       Tuple → TypedTuple 反序列化在 deserializeTupleValues。
    }

    public void demoReverseRangeWithScores() {
        String key = L404Keys.ZSET_LAB + "rrws";
        template.delete(key);
        ops.add(key, "珍珠奶茶", 100);
        ops.add(key, "椰果奶茶", 80);
        ops.add(key, "杨枝甘露", 120);
        Set<TypedTuple<String>> top = ops.reverseRangeWithScores(key, 0, 1);
        System.out.println("[Top2] " + top);
    }

    /**
     * ZRANGEBYSCORE min max —— 按 score 取范围；延时队列拉到期任务的核心。
     */
    public void demoRangeByScore() {
        String key = L404Keys.ZSET_LAB + "rbs";
        template.delete(key);
        ops.add(key, "t1", 100);
        ops.add(key, "t2", 200);
        ops.add(key, "t3", 300);
        System.out.println("[ZRANGEBYSCORE 150 250] " + ops.rangeByScore(key, 150, 250));
    }

    public void demoReverseRangeByScore() {
        String key = L404Keys.ZSET_LAB + "rrbs";
        template.delete(key);
        ops.add(key, "t1", 100);
        ops.add(key, "t2", 200);
        ops.add(key, "t3", 300);
        System.out.println("[ZREVRANGEBYSCORE 250 100] " + ops.reverseRangeByScore(key, 100, 250));
    }

    /**
     * ZREMRANGEBYRANK —— 按下标删除一段。"只保留前 N 名" 的简单实现。
     */
    public void demoRemoveRange() {
        String key = L404Keys.ZSET_LAB + "remrange";
        template.delete(key);
        for (int i = 1; i <= 10; i++) ops.add(key, "x" + i, i);
        // 删除 score 最低的 7 个（保留 top 3）
        ops.removeRange(key, 0, 6);
        System.out.println("[ZREMRANGEBYRANK 0..6] left=" + ops.rangeWithScores(key, 0, -1));
    }

    /**
     * ZREMRANGEBYSCORE —— 按 score 范围删。"删过期任务" 一行搞定。
     */
    public void demoRemoveRangeByScore() {
        String key = L404Keys.ZSET_LAB + "remrbs";
        template.delete(key);
        ops.add(key, "old-1", 100);
        ops.add(key, "old-2", 200);
        ops.add(key, "fresh", 1000);
        ops.removeRangeByScore(key, 0, 500); // 删 score <= 500 的"过期"任务
        System.out.println("[ZREMRANGEBYSCORE 0..500] left=" + ops.range(key, 0, -1));
    }

    /**
     * ZCOUNT min max —— 范围内元素数（不取数据，省带宽）。
     */
    public void demoCount() {
        String key = L404Keys.ZSET_LAB + "count";
        template.delete(key);
        for (int i = 1; i <= 10; i++) ops.add(key, "x" + i, i);
        System.out.println("[ZCOUNT 3..7] " + ops.count(key, 3, 7));
    }

    /**
     * ZCARD —— 集合大小。
     */
    public void demoSize() {
        String key = L404Keys.ZSET_LAB + "size";
        template.delete(key);
        ops.add(key, "a", 1);
        ops.add(key, "b", 2);
        System.out.println("[ZCARD] " + ops.size(key));
    }

    /**
     * ZSCAN —— 游标遍历，大 ZSet 必备。
     */
    public void demoScan() {
        String key = L404Keys.ZSET_LAB + "scan";
        template.delete(key);
        for (int i = 0; i < 30; i++) ops.add(key, "u-" + i, i);
        try (Cursor<TypedTuple<String>> cursor =
                     ops.scan(key, ScanOptions.scanOptions().match("u-*").count(10).build())) {
            int n = 0;
            while (cursor.hasNext() && n < 5) {
                TypedTuple<String> t = cursor.next();
                System.out.println("[ZSCAN] " + t.getValue() + "=" + t.getScore());
                n++;
            }
        }
    }

    public void runAll() {
        demoAdd();
        demoRemove();
        demoScore();
        demoIncrementScore();
        demoRank();
        demoReverseRank();
        demoRange();
        demoReverseRange();
        demoRangeWithScores();
        demoReverseRangeWithScores();
        demoRangeByScore();
        demoReverseRangeByScore();
        demoRemoveRange();
        demoRemoveRangeByScore();
        demoCount();
        demoSize();
        demoScan();
    }
}
