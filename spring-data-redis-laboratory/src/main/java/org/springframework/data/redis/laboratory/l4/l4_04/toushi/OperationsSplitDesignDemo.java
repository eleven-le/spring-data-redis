package org.springframework.data.redis.laboratory.l4.l4_04.toushi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * 偷师 Operations 拆分思想。
 * <p>
 * Spring Data Redis 没有把所有方法塞在 RedisTemplate 这个上帝类里，
 * 而是按数据结构拆出 ListOperations / SetOperations / ZSetOperations 三个独立门面。
 * 调用者通过 redisTemplate.opsForList() / opsForSet() / opsForZSet() 拿到对应 API，
 * 写业务时不会被无关方法干扰。
 * <p>
 * 把这套结构抽出来用到自己的代码：
 * - {@code AppRedisLikeClient} 是大门面（对应 RedisTemplate）。
 * - {@code QueueOperations / DedupOperations / RankingOperations} 是按能力拆出的子门面（对应 ListOperations 三兄弟）。
 * - 子门面有抽象（接口）和默认实现（DefaultXxxOperations），方便替换底层存储。
 */
public class OperationsSplitDesignDemo {

    // ---------------- 子门面接口 ----------------

    interface QueueOperations<T> {
        void enqueue(T item);
        T dequeue();
        int size();
    }

    interface DedupOperations<T> {
        boolean tryAdd(T item);
        boolean contains(T item);
    }

    interface RankingOperations<T> {
        void incrementScore(T item, double delta);
        List<T> topN(int n);
    }

    // ---------------- 默认实现 ----------------

    static class DefaultQueueOperations<T> implements QueueOperations<T> {
        private final LinkedList<T> store = new LinkedList<>();
        public void enqueue(T item) { store.addLast(item); }
        public T dequeue() { return store.isEmpty() ? null : store.removeFirst(); }
        public int size() { return store.size(); }
    }

    static class DefaultDedupOperations<T> implements DedupOperations<T> {
        private final Set<T> store = new HashSet<>();
        public boolean tryAdd(T item) { return store.add(item); }
        public boolean contains(T item) { return store.contains(item); }
    }

    static class DefaultRankingOperations<T> implements RankingOperations<T> {
        private final java.util.Map<T, Double> store = new java.util.HashMap<>();
        public void incrementScore(T item, double delta) {
            store.merge(item, delta, Double::sum);
        }
        public List<T> topN(int n) {
            List<java.util.Map.Entry<T, Double>> list = new ArrayList<>(store.entrySet());
            list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            List<T> result = new ArrayList<>();
            for (int i = 0; i < Math.min(n, list.size()); i++) result.add(list.get(i).getKey());
            return result;
        }
    }

    // ---------------- 大门面：按能力分发 ----------------

    /**
     * 仿 RedisTemplate 的"按能力分发"：opsForXxx 懒生成或单例返回。
     * 业务代码看到的是清爽的子门面，不会面对几十个方法的上帝类。
     */
    static class AppRedisLikeClient {
        private final QueueOperations<String> queueOps = new DefaultQueueOperations<>();
        private final DedupOperations<String> dedupOps = new DefaultDedupOperations<>();
        private final RankingOperations<String> rankingOps = new DefaultRankingOperations<>();

        public QueueOperations<String> opsForQueue() { return queueOps; }
        public DedupOperations<String> opsForDedup() { return dedupOps; }
        public RankingOperations<String> opsForRanking() { return rankingOps; }
    }

    public static void main(String[] args) {
        AppRedisLikeClient client = new AppRedisLikeClient();

        client.opsForQueue().enqueue("ORD-1");
        client.opsForQueue().enqueue("ORD-2");
        System.out.println("[queue] dequeue=" + client.opsForQueue().dequeue() + " size=" + client.opsForQueue().size());

        System.out.println("[dedup] first=" + client.opsForDedup().tryAdd("u-1")
                + " second=" + client.opsForDedup().tryAdd("u-1"));

        client.opsForRanking().incrementScore("珍珠奶茶", 30);
        client.opsForRanking().incrementScore("杨枝甘露", 50);
        client.opsForRanking().incrementScore("椰果奶茶", 20);
        System.out.println("[ranking] top2=" + client.opsForRanking().topN(2));
    }
}
