package org.springframework.data.redis.laboratory.l4.l4_05.toushi;

import java.util.HashSet;
import java.util.Set;

/**
 * 偷师 3：UV 统计策略。
 * <p>
 * 同样的 UV 需求，可以有三种实现，每种各有取舍：
 * <ul>
 *   <li>Set 精确：100% 准确，内存随用户线性增长</li>
 *   <li>HLL 近似：12KB 固定，~0.81% 误差</li>
 *   <li>离线数仓：T+1 准确，不实时</li>
 * </ul>
 * <p>
 * Strategy 模式让"用什么算法"成为可插拔决策——业务方在不同场景挑不同 strategy。
 * <p>
 * 抽象骨架（去掉 Redis 名词）：
 * <pre>
 *   StatService 持有 StatStrategy
 *   StatStrategy = Exact | Approximate | Offline
 * </pre>
 */
public class StatisticStrategyDemo {

    public interface UvStatisticStrategy {
        void record(String bucket, String userId);
        long estimate(String bucket);
        String name();
    }

    /** 精确：内存 Set。1 亿 UV 约 2GB。 */
    public static class ExactSetUvStatisticStrategy implements UvStatisticStrategy {
        private final java.util.Map<String, Set<String>> store = new java.util.concurrent.ConcurrentHashMap<>();
        @Override public void record(String b, String u) { store.computeIfAbsent(b, k -> new HashSet<>()).add(u); }
        @Override public long estimate(String b) { return store.getOrDefault(b, Set.of()).size(); }
        @Override public String name() { return "exact-set"; }
    }

    /** 近似：HLL 类比。误差 ~0.81%，内存固定。 */
    public static class HyperLogLogUvStatisticStrategy implements UvStatisticStrategy {
        private final java.util.Map<String, Long> approx = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Map<String, Set<String>> sample = new java.util.concurrent.ConcurrentHashMap<>();
        @Override public void record(String b, String u) {
            // 演示用：实际 HLL 不存元素。这里仅借助 Set 模拟去重计数行为。
            sample.computeIfAbsent(b, k -> new HashSet<>()).add(u);
            approx.put(b, (long) sample.get(b).size());
        }
        @Override public long estimate(String b) { return approx.getOrDefault(b, 0L); }
        @Override public String name() { return "hll-approx"; }
    }

    /** 离线：T+1 数仓查询。不实时但准确，且能多维度交叉。 */
    public static class OfflineWarehouseUvStatisticStrategy implements UvStatisticStrategy {
        @Override public void record(String b, String u) { /* 写日志，T+1 入仓 */ }
        @Override public long estimate(String b) {
            // 这里返回固定占位，演示"昨天的准确数"
            return 999_999L;
        }
        @Override public String name() { return "offline-warehouse"; }
    }

    /** 服务持有可替换的 strategy，按场景注入不同实现。 */
    public static class UvStatisticService {
        private final UvStatisticStrategy strategy;

        public UvStatisticService(UvStatisticStrategy strategy) {
            this.strategy = strategy;
        }

        public void record(String bucket, String userId) { strategy.record(bucket, userId); }
        public long estimate(String bucket) { return strategy.estimate(bucket); }
        public String strategyName() { return strategy.name(); }
    }

    public static void main(String[] args) {
        UvStatisticService realtime = new UvStatisticService(new HyperLogLogUvStatisticStrategy());
        UvStatisticService strict = new UvStatisticService(new ExactSetUvStatisticStrategy());
        for (int i = 0; i < 100; i++) {
            realtime.record("home:20260501", "u-" + i);
            strict.record("home:20260501", "u-" + (i % 50)); // 模拟去重
        }
        System.out.println("StatisticStrategyDemo: realtime(" + realtime.strategyName() + ")="
                + realtime.estimate("home:20260501") + ", strict(" + strict.strategyName() + ")="
                + strict.estimate("home:20260501"));
    }
}
