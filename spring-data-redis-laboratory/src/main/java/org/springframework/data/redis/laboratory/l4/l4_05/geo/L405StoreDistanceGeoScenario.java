package org.springframework.data.redis.laboratory.l4.l4_05.geo;

import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_05.L405Keys;

import java.util.List;

/**
 * 场景 7.3：门店距离计算。
 * <p>
 * 边界：
 * <ul>
 *   <li>GEODIST 只能算<b>已入库的两个 member</b>之间的距离</li>
 *   <li>用户当前位置不是 member —— 不要为了计算"用户到门店距离" 写一个临时 member 进去再删，
 *       这会污染 ZSet、还增加并发风险</li>
 *   <li>"用户位置 ↔ 门店"距离推荐两种做法：
 *     <ol>
 *       <li>用 GEORADIUS WITHCOORD WITHDIST 一次返回，距离已自动算好（推荐）</li>
 *       <li>应用层 Haversine 公式自己算（坐标对自己掌握时）</li>
 *     </ol>
 *   </li>
 *   <li>批量"用户到 N 个门店"距离：用 GEOPOS 拿 N 个门店坐标 + 应用层批量 Haversine，比 N 次 GEODIST 省 RTT</li>
 * </ul>
 */
public class L405StoreDistanceGeoScenario {

    private final GeoOperations<String, String> ops;

    public L405StoreDistanceGeoScenario(StringRedisTemplate template) {
        this.ops = template.opsForGeo();
    }

    public Long addStore(String cityCode, String storeId, double longitude, double latitude) {
        return ops.add(L405Keys.storeGeo(cityCode), new Point(longitude, latitude), storeId);
    }

    public Distance distance(String cityCode, String storeA, String storeB) {
        return ops.distance(L405Keys.storeGeo(cityCode), storeA, storeB, Metrics.KILOMETERS);
    }

    public Point position(String cityCode, String storeId) {
        List<Point> positions = ops.position(L405Keys.storeGeo(cityCode), storeId);
        return positions == null || positions.isEmpty() ? null : positions.get(0);
    }

    /**
     * Haversine 公式：用户位置到门店的距离（km）。
     * 当门店坐标已在应用层缓存时，绕开 Redis 一次 GEODIST。
     */
    public static double haversineKm(double lon1, double lat1, double lon2, double lat2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return r * c;
    }
}
