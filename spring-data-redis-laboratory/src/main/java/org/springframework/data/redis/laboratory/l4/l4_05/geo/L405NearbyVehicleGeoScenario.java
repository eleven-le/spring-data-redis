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
 * 场景 7.2：附近车辆 / 设备查询。
 * <p>
 * key：{@code l4:05:geo:vehicle:city:{cityCode}}
 * <br>
 * member：vehicleId（车辆唯一编号）
 * <p>
 * 与"附近门店"的本质区别：
 * <ul>
 *   <li><b>位置高频更新</b>：每秒到分钟级 GEOADD（覆盖同一 member 的旧坐标）</li>
 *   <li><b>没有 TTL</b>：GEO member 没有单独过期。下线车辆需要业务侧主动 remove</li>
 *   <li>需要额外维护"心跳时间 / 在线状态"，否则 GEO 里全是几小时前的位置</li>
 * </ul>
 * <p>
 * 工程提示：
 * <ul>
 *   <li>热点城市 GEO key 写 QPS 高 → 单 key 写热点。可拆 {@code city:bucket:0..N} 减压</li>
 *   <li>查询附近车辆要叠加业务过滤：是否在线、是否已被派单、车型匹配 …… 这些都不在 Redis 里</li>
 *   <li><b>Redis GEO ≠ 完整调度系统</b>，只是召回层</li>
 * </ul>
 */
public class L405NearbyVehicleGeoScenario {

    private final GeoOperations<String, String> ops;

    public L405NearbyVehicleGeoScenario(StringRedisTemplate template) {
        this.ops = template.opsForGeo();
    }

    /** 高频更新车辆位置。同 vehicleId 重复 add 会覆盖旧坐标。 */
    public void updateVehicleLocation(String cityCode, String vehicleId, double longitude, double latitude) {
        ops.add(L405Keys.vehicleGeo(cityCode), new Point(longitude, latitude), vehicleId);
    }

    public GeoResults<GeoLocation<String>> searchNearbyVehicles(
            String cityCode, double longitude, double latitude, double radiusKm, long limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须 >0");
        }
        Circle within = new Circle(new Point(longitude, latitude),
                new Distance(radiusKm, Metrics.KILOMETERS));
        GeoRadiusCommandArgs args = GeoRadiusCommandArgs.newGeoRadiusArgs()
                .includeDistance()
                .includeCoordinates()
                .sortAscending()
                .limit(limit);
        return ops.radius(L405Keys.vehicleGeo(cityCode), within, args);
    }

    public Long removeVehicle(String cityCode, String vehicleId) {
        return ops.remove(L405Keys.vehicleGeo(cityCode), vehicleId);
    }
}
