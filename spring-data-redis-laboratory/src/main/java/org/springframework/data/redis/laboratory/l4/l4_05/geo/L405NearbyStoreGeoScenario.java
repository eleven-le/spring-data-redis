package org.springframework.data.redis.laboratory.l4.l4_05.geo;

import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoRadiusCommandArgs;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_05.L405Keys;

/**
 * 场景 7.1：附近 3km 门店查询。
 * <p>
 * key：{@code l4:05:geo:store:city:{cityCode}}
 * <br>
 * member：storeId
 * <p>
 * 为什么按城市拆 key：
 * <ul>
 *   <li>不拆：所有城市挤在一个 ZSet，单 key 千万级 member，内存与查询双重压力</li>
 *   <li>拆：单城市几千到几十万 member 量级；查询天然按城市过滤；不同城市的 GEO key 还可分布到不同分片</li>
 * </ul>
 * <p>
 * <b>注意</b>：Redis GEO 只解决空间召回。营业状态、库存、评分、配送范围必须在应用层做二次过滤
 * （见 {@code L405NearbyStoreRecommendationScenario}）。
 */
public class L405NearbyStoreGeoScenario {

    private final GeoOperations<String, String> ops;

    public L405NearbyStoreGeoScenario(StringRedisTemplate template) {
        this.ops = template.opsForGeo();
    }

    /**
     * 添加门店坐标。注意 longitude 在前。
     */
    public Long addStore(String cityCode, String storeId, double longitude, double latitude) {
        return ops.add(L405Keys.storeGeo(cityCode), new Point(longitude, latitude), storeId);
    }

    public Point getStorePosition(String cityCode, String storeId) {
        var positions = ops.position(L405Keys.storeGeo(cityCode), storeId);
        return positions == null || positions.isEmpty() ? null : positions.get(0);
    }

    public Distance distanceBetweenStores(String cityCode, String storeA, String storeB) {
        return ops.distance(L405Keys.storeGeo(cityCode), storeA, storeB, Metrics.KILOMETERS);
    }

    /**
     * 附近门店搜索。limit 必传，避免一次召回过多。
     */
    public GeoResults<GeoLocation<String>> searchNearbyStores(
            String cityCode, double longitude, double latitude, double radiusKm, long limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须 >0，避免一次返回所有结果");
        }
        Circle within = new Circle(new Point(longitude, latitude),
                new Distance(radiusKm, Metrics.KILOMETERS));
        GeoRadiusCommandArgs args = GeoRadiusCommandArgs.newGeoRadiusArgs()
                .includeDistance()
                .includeCoordinates()
                .sortAscending()
                .limit(limit);
        return ops.radius(L405Keys.storeGeo(cityCode), within, args);
    }

    public Long removeStore(String cityCode, String storeId) {
        return ops.remove(L405Keys.storeGeo(cityCode), storeId);
    }
}
