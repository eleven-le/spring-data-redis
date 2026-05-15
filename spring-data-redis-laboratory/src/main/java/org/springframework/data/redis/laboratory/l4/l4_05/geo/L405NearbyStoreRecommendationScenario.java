package org.springframework.data.redis.laboratory.l4.l4_05.geo;

import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 场景 7.4：附近门店候选召回 + 业务过滤示例。
 * <p>
 * 设计思路（典型双层）：
 * <ol>
 *   <li>第一层：Redis GEO 召回半径内的候选门店 ID（数十到上百个）</li>
 *   <li>第二层：应用层过滤——营业状态、库存、评分、配送范围、用户权限</li>
 * </ol>
 * <p>
 * 为什么不在 Redis 里做完所有过滤：
 * <ul>
 *   <li>Redis GEO 只懂空间，不懂业务</li>
 *   <li>把"营业状态 / 库存"塞 Redis 还需要单独 set，并保持同步，复杂度 ↑</li>
 *   <li>大厂典型组合：Redis GEO 召回 + DB / ES 过滤 + 推荐服务排序</li>
 * </ul>
 * <p>
 * 本示例用进程内 Map 模拟门店元数据（营业状态 / 库存）。真实业务会从 DB / ES / RPC 服务读取。
 */
public class L405NearbyStoreRecommendationScenario {

    private final L405NearbyStoreGeoScenario nearby;

    /** 模拟门店元数据：storeId → 营业状态 / 库存 / 评分。 */
    private final Map<String, StoreMeta> storeMetaTable = new ConcurrentHashMap<>();

    public L405NearbyStoreRecommendationScenario(StringRedisTemplate template) {
        this.nearby = new L405NearbyStoreGeoScenario(template);
    }

    public L405NearbyStoreGeoScenario underlyingGeo() {
        return nearby;
    }

    /** 模拟在内存里登记门店业务元数据（生产代码从 DB 读）。 */
    public void registerStoreMeta(String storeId, boolean opening, int stock, double score) {
        storeMetaTable.put(storeId, new StoreMeta(opening, stock, score));
    }

    /** 第一层：召回附近门店 ID（仅空间）。 */
    public List<String> recallNearbyStoreIds(String cityCode, double longitude, double latitude,
                                             double radiusKm, long limit) {
        GeoResults<GeoLocation<String>> results =
                nearby.searchNearbyStores(cityCode, longitude, latitude, radiusKm, limit);
        List<String> ids = new ArrayList<>();
        if (results != null) {
            for (GeoResult<GeoLocation<String>> r : results) {
                ids.add(r.getContent().getName());
            }
        }
        return ids;
    }

    /**
     * 召回 + 业务过滤 + 简单排序（按评分降序）。
     * 默认过滤条件：营业中 + 库存 > 0。
     */
    public List<String> recommendNearbyStores(String cityCode, double longitude, double latitude) {
        return recommendNearbyStores(cityCode, longitude, latitude, 3.0, 50,
                m -> m.opening && m.stock > 0);
    }

    public List<String> recommendNearbyStores(String cityCode, double longitude, double latitude,
                                              double radiusKm, long limit,
                                              Predicate<StoreMeta> businessFilter) {
        List<String> candidates = recallNearbyStoreIds(cityCode, longitude, latitude, radiusKm, limit);
        List<String> filtered = new ArrayList<>();
        for (String id : candidates) {
            StoreMeta meta = storeMetaTable.get(id);
            if (meta != null && businessFilter.test(meta)) {
                filtered.add(id);
            }
        }
        // 简单按评分降序，演示二次排序通常需要业务方法做
        filtered.sort((a, b) -> Double.compare(
                storeMetaTable.get(b).score, storeMetaTable.get(a).score));
        return filtered;
    }

    public static class StoreMeta {
        public final boolean opening;
        public final int stock;
        public final double score;

        public StoreMeta(boolean opening, int stock, double score) {
            this.opening = opening;
            this.stock = stock;
            this.score = score;
        }
    }
}
