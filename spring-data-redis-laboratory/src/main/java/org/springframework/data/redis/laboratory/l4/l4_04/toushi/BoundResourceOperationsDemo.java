package org.springframework.data.redis.laboratory.l4.l4_04.toushi;

import java.util.LinkedList;
import java.util.List;
import java.util.TreeMap;

/**
 * 偷师 BoundOperations 模式。
 * <p>
 * 直接 ListOperations / ZSetOperations 每个方法都要传 key，调用方需要在每行业务代码上重复同一个 key。
 * BoundListOperations / BoundZSetOperations 把"key" 在工厂方法里就绑死，
 * 后续调用就像在操作"以这个 key 为身份的领域对象"，业务可读性大幅提升。
 * <p>
 * 把这套思路用到自己的代码里：
 * - 每个用户的"最近浏览"= UserRecentViewOperations，绑定 userId
 * - 每天的"销量榜" = DailyRankingOperations，绑定 yyyyMMdd
 * - 每场活动的"抽奖池" = ActivityLotteryOperations，绑定 activityId
 * <p>
 * 进一步抽象：BoundResourceOperations 像"一个 key 的领域对象"，
 * 你之后增加业务能力（比如"统计 / 序列化 / 审计"）只要改一处。
 */
public class BoundResourceOperationsDemo {

    /* ============== 通用 BoundOperations 抽象 ============== */
    interface BoundResourceOperations<K, V> {
        K key();
        long size();
    }

    /* ============== 最近浏览 ============== */
    interface UserRecentViewOperations {
        void addView(String itemId);
        List<String> recent(int limit);
        long size();
    }

    static class BoundUserRecentViewOperations implements UserRecentViewOperations,
            BoundResourceOperations<String, String> {
        private final String userId;
        private final LinkedList<String> store = new LinkedList<>();
        private final int maxSize;

        public BoundUserRecentViewOperations(String userId, int maxSize) {
            this.userId = userId;
            this.maxSize = maxSize;
        }
        public String key() { return "user:" + userId + ":recent"; }
        public long size() { return store.size(); }
        public void addView(String itemId) {
            store.remove(itemId);
            store.addFirst(itemId);
            while (store.size() > maxSize) store.removeLast();
        }
        public List<String> recent(int limit) {
            return store.subList(0, Math.min(limit, store.size()));
        }
    }

    /* ============== 每日销量榜 ============== */
    interface DailyRankingOperations {
        void incrementScore(String itemId, double delta);
        List<String> topN(int n);
    }

    static class BoundDailyRankingOperations implements DailyRankingOperations,
            BoundResourceOperations<String, String> {
        private final String day;
        private final TreeMap<String, Double> store = new TreeMap<>();

        public BoundDailyRankingOperations(String day) { this.day = day; }
        public String key() { return "rank:sales:" + day; }
        public long size() { return store.size(); }
        public void incrementScore(String itemId, double delta) {
            store.merge(itemId, delta, Double::sum);
        }
        public List<String> topN(int n) {
            return store.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(n)
                    .map(java.util.Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    public static void main(String[] args) {
        BoundUserRecentViewOperations rv = new BoundUserRecentViewOperations("u-2001", 3);
        rv.addView("item-A");
        rv.addView("item-B");
        rv.addView("item-A"); // 去重提前
        rv.addView("item-C");
        rv.addView("item-D"); // 超过 maxSize=3，淘汰最旧
        System.out.println("[bound recentView key=" + rv.key() + "] " + rv.recent(10));

        BoundDailyRankingOperations rank = new BoundDailyRankingOperations("20260430");
        rank.incrementScore("珍珠奶茶", 30);
        rank.incrementScore("杨枝甘露", 50);
        rank.incrementScore("珍珠奶茶", 5);
        rank.incrementScore("椰果奶茶", 20);
        System.out.println("[bound rank key=" + rank.key() + "] top3=" + rank.topN(3));
    }
}
