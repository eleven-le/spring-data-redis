package org.springframework.data.redis.laboratory.l4.l4_07.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_07.L407Keys;
import org.springframework.data.redis.laboratory.l4.l4_07.config.L407RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_07.scenario.L407BatchDeleteCacheScenario;
import org.springframework.data.redis.laboratory.l4.l4_07.scenario.L407HomeAggregateCacheScenario;
import org.springframework.data.redis.laboratory.l4.l4_07.scenario.L407MixedCommandPipelineScenario;
import org.springframework.data.redis.laboratory.l4.l4_07.scenario.L407ProductCacheWarmupScenario;
import org.springframework.data.redis.laboratory.l4.l4_07.scenario.L407RankingPipelineScenario;
import org.springframework.data.redis.laboratory.l4.l4_07.scenario.L407UserBehaviorCounterPipelineScenario;
import org.springframework.data.redis.laboratory.l4.l4_07.pipeline.BatchMetrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实业务场景调试入口。
 * <p>
 * <b>建议断点位置</b>：
 * <ol>
 *   <li>各 scenario 内 executePipelined 调用</li>
 *   <li>{@code RedisTemplate#executePipelined(SessionCallback)}</li>
 *   <li>{@code DefaultZSetOperations#reverseRangeWithScores}（混合命令场景）</li>
 *   <li>{@code DefaultHashOperations#get}（混合命令场景）</li>
 *   <li>{@code LettuceConnection#closePipeline}</li>
 * </ol>
 */
public class L407PipelineScenarioDebugMain {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(L407RedisConfig.class)) {

            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);

            System.out.println("===== L4-07 Pipeline Scenario Debug =====");

            // 6.1 首页聚合
            L407HomeAggregateCacheScenario home = new L407HomeAggregateCacheScenario(template);
            List<String> homeKeys = L407HomeAggregateCacheScenario.buildDemoHomeKeys();
            home.prepareDemoData(homeKeys);
            L407HomeAggregateCacheScenario.CompareResult cmp = home.compareThreeWays(homeKeys);
            System.out.println("[home] " + cmp);

            // 6.2 商品预热
            L407ProductCacheWarmupScenario warm = new L407ProductCacheWarmupScenario(template);
            BatchMetrics warmMetrics = warm.warmupProductsInBatches(L407ProductCacheWarmupScenario.buildDemoProducts(2000), 500);
            System.out.println("[warmup] " + warmMetrics);

            // 6.3 用户行为计数
            L407UserBehaviorCounterPipelineScenario counter = new L407UserBehaviorCounterPipelineScenario(template);
            Map<String, Long> deltaMap = new LinkedHashMap<>();
            deltaMap.put(L407Keys.counter("product-view", "p1"), 1L);
            deltaMap.put(L407Keys.counter("shop-view", "s1"), 1L);
            deltaMap.put(L407Keys.counter("user-visit", "u1"), 1L);
            deltaMap.put(L407Keys.counter("activity-click", "a1"), 1L);
            deltaMap.put(L407Keys.counter("search-keyword", "milktea"), 1L);
            Map<String, Long> after = counter.increaseCountersWithDelta(deltaMap);
            System.out.println("[counter] " + after);
            counter.clearCounters(after.keySet().stream().toList());

            // 6.4 榜单
            L407RankingPipelineScenario rank = new L407RankingPipelineScenario(template);
            String rankKey = L407Keys.rank("product-sales", "20260501");
            rank.clearRank(rankKey);
            Map<String, Double> deltaScores = new LinkedHashMap<>();
            for (int i = 1; i <= 30; i++) {
                deltaScores.put("p" + i, (double) (i * 3));
            }
            rank.batchIncreaseScores(rankKey, deltaScores);
            System.out.println("[rank] Top 5 = " + rank.getTopN(rankKey, 5));
            rank.clearRank(rankKey);

            // 6.5 批量删除
            L407BatchDeleteCacheScenario del = new L407BatchDeleteCacheScenario(template);
            L407BatchDeleteCacheScenario.CompareDeleteWaysResult delResult = del.compareDeleteWays(2000, 500);
            System.out.println("[delete] pipeline=" + delResult.pipelineCostMs() + "ms"
                    + ", unlink=" + delResult.unlinkCostMs() + "ms"
                    + ", template.delete=" + delResult.templateDeleteCostMs() + "ms"
                    + ", deletedReturned=" + delResult.templateDeleteReturned());

            // 6.6 混合命令
            L407MixedCommandPipelineScenario mixed = new L407MixedCommandPipelineScenario(template);
            String userId = "u-1001";
            String activityId = "act-501";
            mixed.prepareDemoData(userId, activityId);
            List<Object> rawMixed = mixed.executeHomePageMixedPipeline(userId, activityId);
            System.out.println("[mixed-raw] size=" + rawMixed.size());
            L407MixedCommandPipelineScenario.HomePageAggregate agg = mixed.mapMixedResults(rawMixed);
            System.out.println("[mixed-mapped] " + agg);
            System.out.println("===== Pipeline Scenario Debug Done =====");
        }
    }
}
