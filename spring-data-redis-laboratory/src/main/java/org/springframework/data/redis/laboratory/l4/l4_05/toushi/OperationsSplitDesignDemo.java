package org.springframework.data.redis.laboratory.l4.l4_05.toushi;

/**
 * 偷师 1：Operations 拆分设计。
 * <p>
 * SDR 把 RedisTemplate 按数据结构拆出 opsForHyperLogLog / opsForValue / opsForGeo。
 * 这套设计并非 Redis 专属——所有"门面方法太多"的客户端都该这么拆。
 * <p>
 * 本 Demo 模拟一个茶饮 C 端的 AnalyticsClient（埋点 / 统计聚合服务）：
 * 不要把 100+ 方法都挂在一个大 Client 上，按业务能力拆 opsForUv / opsForCheckin / opsForLocation。
 * <p>
 * 抽象骨架：
 * <pre>
 *   AnalyticsClient
 *     ├─ opsForUv()        → UvOperations         （HLL 类比）
 *     ├─ opsForCheckin()   → CheckinOperations    （Bitmap 类比）
 *     └─ opsForLocation()  → LocationOperations   （GEO 类比）
 * </pre>
 */
public class OperationsSplitDesignDemo {

    /** 大门面：一个 client，按能力拆出三个子门面。 */
    public static class AnalyticsClient {
        private final UvOperations uvOps = new DefaultUvOperations();
        private final CheckinOperations checkinOps = new DefaultCheckinOperations();
        private final LocationOperations locationOps = new DefaultLocationOperations();

        public UvOperations opsForUv() { return uvOps; }
        public CheckinOperations opsForCheckin() { return checkinOps; }
        public LocationOperations opsForLocation() { return locationOps; }
    }

    public interface UvOperations {
        void record(String bucket, String userId);
        long size(String bucket);
    }

    public interface CheckinOperations {
        void mark(String userId, int day);
        boolean isChecked(String userId, int day);
    }

    public interface LocationOperations {
        void update(String resourceId, double lon, double lat);
        boolean within(String resourceId, double lon, double lat, double radiusKm);
    }

    /** 默认实现：示意性占位，业务里替换成对接真实埋点 / Redis / Kafka。 */
    public static class DefaultUvOperations implements UvOperations {
        @Override public void record(String bucket, String userId) { /* push 到 HLL / Kafka */ }
        @Override public long size(String bucket) { return 0; }
    }
    public static class DefaultCheckinOperations implements CheckinOperations {
        @Override public void mark(String userId, int day) { /* setBit */ }
        @Override public boolean isChecked(String userId, int day) { return false; }
    }
    public static class DefaultLocationOperations implements LocationOperations {
        @Override public void update(String resourceId, double lon, double lat) { /* GEOADD */ }
        @Override public boolean within(String resourceId, double lon, double lat, double radiusKm) { return false; }
    }

    public static void main(String[] args) {
        AnalyticsClient client = new AnalyticsClient();
        client.opsForUv().record("home:20260501", "u-1");
        client.opsForCheckin().mark("u-1", 1);
        client.opsForLocation().update("store-001", 116.40, 39.91);
        System.out.println("OperationsSplitDesignDemo: client 三个子门面调用完毕");
    }
}
