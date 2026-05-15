package org.springframework.data.redis.laboratory.l4.l4_07.scenario;

import org.springframework.data.redis.laboratory.l4.l4_07.pipeline.L407Pipelines;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.laboratory.l4.l4_07.L407Keys;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 场景 6.6：混合命令 Pipeline（Pipeline 真正的杀手锏）。
 * <p>
 * <b>业务背景</b>：用户访问首页时，Web 端需要一次性拿到：
 *   - 用户昵称（GET）
 *   - 用户等级（HGET）
 *   - 商品热榜 Top 10（ZREVRANGE）
 *   - 活动参与人数（SCARD）
 *   - 接口访问 +1（INCR）
 * <p>
 * 5 条命令分别走不同 RedisTemplate 子门面，但都能放进一个 Pipeline，一次 RTT 全部带回。
 * <p>
 * <b>关键点</b>：
 *   1) Pipeline 可以混合任意命令、任意 key、任意数据结构；
 *   2) 但需要业务自己维护一个 command plan，结果按 index 解析；
 *   3) 不要乱依赖前一条命令的结果——返回值在 closePipeline 之后才回来；
 *   4) 不要假定原子；
 *   5) 推荐结合 ResultMapper（参考 toushi/ResultMapperDesignDemo）封装，避免业务里散落强转。
 */
public class L407MixedCommandPipelineScenario {

    private final StringRedisTemplate template;

    public L407MixedCommandPipelineScenario(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * 一条命令计划。
     */
    public enum MixedCommand {
        GET_USER_NICK,
        HGET_USER_LEVEL,
        ZREVRANGE_HOT_PRODUCT_TOP10,
        SCARD_ACTIVITY_JOIN,
        INCR_API_VISIT
    }

    public List<MixedCommand> buildCommandPlan() {
        List<MixedCommand> plan = new ArrayList<>();
        plan.add(MixedCommand.GET_USER_NICK);
        plan.add(MixedCommand.HGET_USER_LEVEL);
        plan.add(MixedCommand.ZREVRANGE_HOT_PRODUCT_TOP10);
        plan.add(MixedCommand.SCARD_ACTIVITY_JOIN);
        plan.add(MixedCommand.INCR_API_VISIT);
        return plan;
    }

    /**
     * 准备 demo 数据：写昵称、等级、热榜、活动参与人。
     */
    public void prepareDemoData(String userId, String activityId) {
        template.opsForValue().set(L407Keys.mixedUserNick(userId), "leiyuhang", Duration.ofMinutes(30));
        template.opsForHash().put(L407Keys.mixedUserProfile(userId), "level", "VIP6");
        template.opsForHash().put(L407Keys.mixedUserProfile(userId), "city", "Shanghai");
        template.expire(L407Keys.mixedUserProfile(userId), Duration.ofMinutes(30));

        String hotRank = L407Keys.mixedHotProductRank();
        template.delete(hotRank);
        for (int i = 1; i <= 20; i++) {
            template.opsForZSet().add(hotRank, "p" + i, i * 10d);
        }

        String joinSet = L407Keys.mixedActivityJoinSet(activityId);
        template.delete(joinSet);
        for (int i = 0; i < 100; i++) {
            template.opsForSet().add(joinSet, "user-" + i);
        }
    }

    /**
     * 关键方法：执行混合 Pipeline。
     * <p>
     * 返回 List 顺序 == buildCommandPlan() 顺序。
     */
    public List<Object> executeHomePageMixedPipeline(String userId, String activityId) {
        return L407Pipelines.run(template, ops -> {
            // 0. GET 用户昵称
            ops.opsForValue().get(L407Keys.mixedUserNick(userId));
            // 1. HGET 用户等级
            ops.opsForHash().get(L407Keys.mixedUserProfile(userId), "level");
            // 2. ZREVRANGE 商品热榜 Top 10
            ops.opsForZSet().reverseRangeWithScores(L407Keys.mixedHotProductRank(), 0, 9);
            // 3. SCARD 活动参与人数
            ops.opsForSet().size(L407Keys.mixedActivityJoinSet(activityId));
            // 4. INCR 接口访问次数
            ops.opsForValue().increment(L407Keys.mixedApiVisit("home"));
            return null;
        });
    }

    /**
     * 把 mixed results 按 plan 解析成业务对象。
     * <p>
     * 这一步是"结果映射"的核心：业务不应让 (Long) (Set) 强转散落到上层 service 里，
     * 应该集中在一个 mapper / parser，方便维护和单测。
     */
    public HomePageAggregate mapMixedResults(List<Object> results) {
        HomePageAggregate agg = new HomePageAggregate();

        Object r0 = results.get(0);
        agg.nick = r0 == null ? null : r0.toString();

        Object r1 = results.get(1);
        agg.level = r1 == null ? null : r1.toString();

        Object r2 = results.get(2);
        if (r2 instanceof Set<?> set) {
            Map<String, Double> top = new LinkedHashMap<>();
            for (Object item : set) {
                if (item instanceof ZSetOperations.TypedTuple<?> tuple) {
                    top.put(String.valueOf(tuple.getValue()), tuple.getScore());
                }
            }
            agg.hotTop10 = top;
        }

        Object r3 = results.get(3);
        agg.activityJoinCount = r3 == null ? 0L : ((Number) r3).longValue();

        Object r4 = results.get(4);
        agg.apiVisit = r4 == null ? 0L : ((Number) r4).longValue();

        return agg;
    }

    public static class HomePageAggregate {
        public String nick;
        public String level;
        public Map<String, Double> hotTop10 = new LinkedHashMap<>();
        public long activityJoinCount;
        public long apiVisit;

        @Override
        public String toString() {
            return "HomePageAggregate{nick='" + nick + '\''
                    + ", level='" + level + '\''
                    + ", hotTop10=" + hotTop10
                    + ", activityJoinCount=" + activityJoinCount
                    + ", apiVisit=" + apiVisit
                    + '}';
        }
    }
}
