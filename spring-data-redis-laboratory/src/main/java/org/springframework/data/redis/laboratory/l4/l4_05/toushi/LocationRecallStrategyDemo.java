package org.springframework.data.redis.laboratory.l4.l4_05.toushi;

import java.util.List;

/**
 * 偷师 4：附近召回策略。
 * <p>
 * 不同后端各有所长：
 * <ul>
 *   <li>Redis GEO：轻量、毫秒级、单一空间维度，适合附近召回</li>
 *   <li>ES Geo：多条件检索 / 多维度过滤，适合"附近 + 标签 + 业务过滤"复合查询</li>
 *   <li>地图服务：真实路径规划 / 道路距离 / 围栏，适合复杂 GIS</li>
 * </ul>
 * <p>
 * NearbyStoreService 持有 LocationRecallStrategy，在不同业务场景注入不同实现。
 * 业务侧不应直接绑死 RedisGeoOperations。
 */
public class LocationRecallStrategyDemo {

    public interface LocationRecallStrategy {
        List<String> recall(double lon, double lat, double radiusKm, int limit);
        String name();
    }

    public static class RedisGeoLocationRecallStrategy implements LocationRecallStrategy {
        @Override
        public List<String> recall(double lon, double lat, double radiusKm, int limit) {
            return List.of("redis-store-1", "redis-store-2");
        }
        @Override public String name() { return "redis-geo"; }
    }

    public static class EsGeoLocationRecallStrategy implements LocationRecallStrategy {
        @Override
        public List<String> recall(double lon, double lat, double radiusKm, int limit) {
            return List.of("es-store-1");
        }
        @Override public String name() { return "es-geo"; }
    }

    public static class MapServiceLocationRecallStrategy implements LocationRecallStrategy {
        @Override
        public List<String> recall(double lon, double lat, double radiusKm, int limit) {
            return List.of("amap-store-A");
        }
        @Override public String name() { return "amap-service"; }
    }

    public static class NearbyStoreService {
        private final LocationRecallStrategy strategy;

        public NearbyStoreService(LocationRecallStrategy strategy) {
            this.strategy = strategy;
        }

        public List<String> nearby(double lon, double lat, double radiusKm, int limit) {
            return strategy.recall(lon, lat, radiusKm, limit);
        }
    }

    public static void main(String[] args) {
        NearbyStoreService quick = new NearbyStoreService(new RedisGeoLocationRecallStrategy());
        NearbyStoreService rich = new NearbyStoreService(new EsGeoLocationRecallStrategy());
        NearbyStoreService gis = new NearbyStoreService(new MapServiceLocationRecallStrategy());
        System.out.println("LocationRecallStrategyDemo:");
        System.out.println("  quick → " + quick.nearby(116.4, 39.9, 3, 10));
        System.out.println("  rich  → " + rich.nearby(116.4, 39.9, 3, 10));
        System.out.println("  gis   → " + gis.nearby(116.4, 39.9, 3, 10));
    }
}
