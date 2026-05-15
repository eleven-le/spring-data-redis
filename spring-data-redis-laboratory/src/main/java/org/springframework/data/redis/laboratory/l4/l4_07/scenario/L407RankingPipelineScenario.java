package org.springframework.data.redis.laboratory.l4.l4_07.scenario;

import org.springframework.data.redis.laboratory.l4.l4_07.pipeline.L407Pipelines;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 场景 6.4：榜单批量写入（ZADD / ZINCRBY）。
 * <p>
 * <b>业务背景</b>：商品销量榜、内容热榜、门店热榜。
 * 每次活动结束 / 实时计算引擎触发，会一次性向 Redis 推送几千条 score 更新。
 * <p>
 * <b>关键点</b>：
 * 1) 单条 ZADD / ZINCRBY 是原子的；Pipeline 不会改变这个；
 * 2) Pipeline 不解决"刷榜风控"；风控要在写入前做（Lua / 黑名单 / 频控）；
 * 3) 榜单的最终事实源在 OLAP / DB，Redis 只负责"快速读取 Top N"；
 * 4) Pipeline 之外，集群模式下要求所有 member 在同一 slot —— 这里榜单 key 是单个 zset，无 slot 冲突问题。
 */
public class L407RankingPipelineScenario {

    private final StringRedisTemplate template;

    public L407RankingPipelineScenario(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * 批量 ZINCRBY：把 memberScoreDeltaMap 中的每个 member 的 score 累加。
     * <p>
     * 例：商品 A 销量 +3，商品 B 销量 +5。
     */
    public List<Object> batchIncreaseScores(String rankKey,
                                            Map<String, Double> memberScoreDeltaMap) {
        List<String> orderedMembers = new java.util.ArrayList<>(memberScoreDeltaMap.keySet());
        return L407Pipelines.run(template, ops -> {
            for (String member : orderedMembers) {
                ops.opsForZSet().incrementScore(rankKey, member, memberScoreDeltaMap.get(member));
            }
            return null;
        });
    }

    /**
     * 批量 ZADD：直接覆盖 score。
     * <p>
     * 适用场景：每次都是"最新分数"覆盖（比如离线计算结果回写）。
     */
    public List<Object> batchAddScores(String rankKey, Map<String, Double> memberScoreMap) {
        List<String> orderedMembers = new java.util.ArrayList<>(memberScoreMap.keySet());
        return L407Pipelines.run(template, ops -> {
            for (String member : orderedMembers) {
                ops.opsForZSet().add(rankKey, member, memberScoreMap.get(member));
            }
            return null;
        });
    }

    /**
     * 取 Top N（按 score 倒序），用 LinkedHashMap 保证顺序。
     */
    public Map<String, Double> getTopN(String rankKey, int n) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                template.opsForZSet().reverseRangeWithScores(rankKey, 0, n - 1);
        if (tuples == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Double> map = new LinkedHashMap<>();
        for (ZSetOperations.TypedTuple<String> t : tuples) {
            if (t.getValue() != null) {
                map.put(t.getValue(), t.getScore());
            }
        }
        return map;
    }

    /**
     * 清空榜单（demo 后清场）。生产严禁随便 DEL 大 zset。
     */
    public void clearRank(String rankKey) {
        template.delete(rankKey);
    }

    /**
     * 工具：包装一个 LinkedHashSet 视图。仅供 demo 输出可读。
     */
    public static Set<String> orderedSet(Iterable<String> members) {
        Set<String> set = new LinkedHashSet<>();
        for (String m : members) {
            set.add(m);
        }
        return set;
    }
}
