package org.springframework.data.redis.laboratory.l4.l4_04.toushi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 偷师 ZSet score 策略模式。
 * <p>
 * ZSet 自身只关心"score 是 double，按 score 排序"。
 * 但业务上，"销量榜""热度榜""时间优先级队列"对 score 的算法完全不同：
 * - 销量榜：score = 累积销量
 * - 热度榜：score = a*like + b*comment + c*view * 时间衰减
 * - 时间优先级：score = 任务到期时间戳
 * <p>
 * 把"score 怎么算" 抽成 ScoreStrategy 接口，让 RankingService 和具体公式解耦。
 * 这就是 SDR 里很常见的"行为做接口、ZSet 只负责存"。
 * 同样的结构在业务里能用于：
 * - 推荐打分：BaseScoreStrategy / BoostedScoreStrategy / DiversityScoreStrategy
 * - 风控：每种规则一个 Strategy
 * - 优惠券满减：满减、折扣、固定面额各一个 Strategy
 */
public class RankingStrategyDemo {

    interface ScoreStrategy<T> {
        double computeScore(T item, ScoreContext ctx);
    }

    static class ScoreContext {
        public final Map<String, Object> stats;
        public final long now;
        public ScoreContext(Map<String, Object> stats) {
            this.stats = stats;
            this.now = System.currentTimeMillis();
        }
    }

    /** 销量策略：score = 当前累积销量。 */
    static class SalesScoreStrategy implements ScoreStrategy<String> {
        public double computeScore(String item, ScoreContext ctx) {
            return ((Number) ctx.stats.getOrDefault("sales", 0)).doubleValue();
        }
    }

    /** 热度策略：score = 0.6*like + 0.3*comment + 0.1*view，再做小时衰减。 */
    static class HotContentScoreStrategy implements ScoreStrategy<String> {
        public double computeScore(String item, ScoreContext ctx) {
            double like    = ((Number) ctx.stats.getOrDefault("like", 0)).doubleValue();
            double comment = ((Number) ctx.stats.getOrDefault("comment", 0)).doubleValue();
            double view    = ((Number) ctx.stats.getOrDefault("view", 0)).doubleValue();
            long ageHours  = ((Number) ctx.stats.getOrDefault("ageHours", 0L)).longValue();
            double raw = 0.6 * like + 0.3 * comment + 0.1 * view;
            return raw / Math.pow(ageHours + 2, 1.5); // 越久衰减越多
        }
    }

    /** 时间优先级策略：score = 到期时间戳，越早越先消费。 */
    static class TimePriorityScoreStrategy implements ScoreStrategy<String> {
        public double computeScore(String item, ScoreContext ctx) {
            return ((Number) ctx.stats.getOrDefault("dueAt", ctx.now)).doubleValue();
        }
    }

    /** 排序服务：业务只交"用什么策略" + "原始数据"，结果是一份榜单。 */
    static class RankingService {
        private final ScoreStrategy<String> strategy;
        public RankingService(ScoreStrategy<String> strategy) { this.strategy = strategy; }
        public List<String> rank(Map<String, Map<String, Object>> input, int topN, boolean ascending) {
            List<ScoredItem> list = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> e : input.entrySet()) {
                list.add(new ScoredItem(e.getKey(), strategy.computeScore(e.getKey(), new ScoreContext(e.getValue()))));
            }
            list.sort((a, b) -> ascending ? Double.compare(a.s, b.s) : Double.compare(b.s, a.s));
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < Math.min(topN, list.size()); i++) ids.add(list.get(i).id);
            return ids;
        }
        private static final class ScoredItem {
            final String id; final double s;
            ScoredItem(String id, double s) { this.id = id; this.s = s; }
        }
    }

    public static void main(String[] args) {
        Map<String, Map<String, Object>> sales = new HashMap<>();
        sales.put("珍珠奶茶", Map.of("sales", 320));
        sales.put("椰果奶茶", Map.of("sales", 220));
        sales.put("杨枝甘露", Map.of("sales", 410));
        System.out.println("[销量榜 Top2] " + new RankingService(new SalesScoreStrategy()).rank(sales, 2, false));

        Map<String, Map<String, Object>> hot = new HashMap<>();
        hot.put("c-1", Map.of("like", 1000, "comment", 200, "view", 50000, "ageHours", 1L));
        hot.put("c-2", Map.of("like", 5000, "comment", 800, "view", 80000, "ageHours", 12L));
        hot.put("c-3", Map.of("like", 800,  "comment", 100, "view", 30000, "ageHours", 0L));
        System.out.println("[热度榜 Top3] " + new RankingService(new HotContentScoreStrategy()).rank(hot, 3, false));

        Map<String, Map<String, Object>> tasks = new HashMap<>();
        long now = System.currentTimeMillis();
        tasks.put("close-9001", Map.of("dueAt", now + 1000));
        tasks.put("close-9002", Map.of("dueAt", now + 5000));
        tasks.put("close-9003", Map.of("dueAt", now + 200));
        // 时间优先：score 小的先消费，所以 ascending=true
        System.out.println("[时间队列] " + new RankingService(new TimePriorityScoreStrategy()).rank(tasks, 3, true));
    }
}
