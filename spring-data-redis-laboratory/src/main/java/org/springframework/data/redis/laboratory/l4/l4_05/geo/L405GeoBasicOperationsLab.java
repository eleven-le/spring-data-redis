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

import java.util.List;

/**
 * L4-05 GEO 命令全家桶（基础实验）。
 * <p>
 * Redis GEO 底层是 ZSet：member 是业务对象 ID，score 是 GeoHash 编码后的 52-bit 数值。
 * GEOADD = ZADD 一个 geohash score。所以"附近"查询本质是 ZRANGEBYSCORE 解码。
 * <p>
 * <b>经纬度顺序</b>：Spring 的 {@code Point(x, y)} 中 x = longitude，y = latitude。
 * 顺序写反是本章新手踩坑率最高的一个 bug。北京中心点经纬度约为 (116.40, 39.90)，longitude 在前。
 * <p>
 * 通用源码链路：
 * <pre>
 * RedisTemplate.opsForGeo()
 *   → DefaultGeoOperations.add/radius/distance
 *     → RedisTemplate.execute(callback)
 *       → LettuceConnection.geoCommands().geoAdd/geoRadius/geoDist
 * </pre>
 */
public class L405GeoBasicOperationsLab {

    private final StringRedisTemplate template;
    private final GeoOperations<String, String> ops;

    public L405GeoBasicOperationsLab(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForGeo();
    }

    /**
     * GEOADD —— 加入坐标。
     * SDR API: GeoOperations.add(K, Point, M)
     * 返回值：新加入的 member 数（已存在覆盖坐标，返回 0）。
     * 坑：Point(longitude, latitude) 顺序！
     */
    public Long add(String key, double longitude, double latitude, String member) {
        return ops.add(key, new Point(longitude, latitude), member);
    }

    /**
     * GEOPOS —— 查询 member 的坐标。不存在返回 null。
     * SDR API: GeoOperations.position(K, M...)
     */
    public List<Point> position(String key, String... members) {
        return ops.position(key, members);
    }

    /**
     * GEODIST —— 计算两个 member 的球面距离。
     * SDR API: GeoOperations.distance(K, M, M, Metric)
     * 任一 member 不存在返回 null。
     */
    public Distance distance(String key, String memberA, String memberB) {
        return ops.distance(key, memberA, memberB, Metrics.KILOMETERS);
    }

    /**
     * GEORADIUS —— 圆形范围查询（默认参数）。
     * 不带 args 时只返回 member，没有距离 / 坐标 / 排序信息。
     */
    public GeoResults<GeoLocation<String>> radius(String key, double longitude, double latitude, double radiusKm) {
        Circle within = new Circle(new Point(longitude, latitude), new Distance(radiusKm, Metrics.KILOMETERS));
        return ops.radius(key, within);
    }

    /**
     * GEORADIUS WITHCOORD WITHDIST COUNT N ASC —— 推荐的"业务查附近"组合。
     * <ul>
     *   <li>includeDistance —— 返回距离</li>
     *   <li>includeCoordinates —— 返回坐标</li>
     *   <li>sortAscending —— 最近优先（必加，否则结果顺序未定义）</li>
     *   <li>limit —— 必加，避免一次返回数千条</li>
     * </ul>
     */
    public GeoResults<GeoLocation<String>> radiusWithArgs(
            String key, double longitude, double latitude, double radiusKm, long limit) {
        Circle within = new Circle(new Point(longitude, latitude), new Distance(radiusKm, Metrics.KILOMETERS));
        GeoRadiusCommandArgs args = GeoRadiusCommandArgs.newGeoRadiusArgs()
                .includeDistance()
                .includeCoordinates()
                .sortAscending()
                .limit(limit);
        return ops.radius(key, within, args);
    }

    /**
     * GEOHASH —— 返回 11 字符 GeoHash 字符串（业务很少用）。
     */
    public List<String> hash(String key, String... members) {
        return ops.hash(key, members);
    }

    /**
     * 移除 member。
     * <b>注意</b>：GEO 没有"按距离/坐标删除"的命令，只能按 member 删；
     * GEO member <b>没有单独 TTL</b>，只能整个 key 过期。
     */
    public Long remove(String key, String... members) {
        return ops.remove(key, members);
    }
}
