package org.springframework.data.redis.laboratory.l4.l4_05.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_05.config.L405RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_05.geo.L405GeoBasicOperationsLab;
import org.springframework.data.redis.laboratory.l4.l4_05.geo.L405NearbyStoreGeoScenario;
import org.springframework.data.redis.laboratory.l4.l4_05.geo.L405NearbyStoreRecommendationScenario;
import org.springframework.data.redis.laboratory.l4.l4_05.geo.L405NearbyVehicleGeoScenario;
import org.springframework.data.redis.laboratory.l4.l4_05.geo.L405StoreDistanceGeoScenario;

import java.util.List;

/**
 * GEO 调试入口。
 * <p>
 * <b>建议断点位置</b>：
 * <ol>
 *   <li>{@code RedisTemplate#opsForGeo} —— 看 GeoOperations 子门面</li>
 *   <li>{@code DefaultGeoOperations#add} —— 看 Point + member 如何序列化</li>
 *   <li>{@code DefaultGeoOperations#radius(K, Circle, GeoRadiusCommandArgs)} —— 看 args 如何下发</li>
 *   <li>{@code RedisTemplate#execute(RedisCallback)}</li>
 *   <li>{@code RedisConnectionUtils#doGetConnection}</li>
 *   <li>{@code LettuceConnection#geoCommands} —— 桥接到 Lettuce 的 GEO 命令族</li>
 * </ol>
 */
public class L405GeoDebugMain {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(L405RedisConfig.class)) {

            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);
            String city = "BJ";

            System.out.println("===== L4-05 GEO Debug =====");

            // ---------- 基础实验 ----------
            L405GeoBasicOperationsLab lab = new L405GeoBasicOperationsLab(template);
            String labKey = "l4:05:geo:lab:demo";
            template.delete(labKey);
            // 北京附近 3 个虚构门店：天安门、王府井、西单
            lab.add(labKey, 116.397128, 39.916527, "store-tiananmen");
            lab.add(labKey, 116.418099, 39.917545, "store-wangfujing");
            lab.add(labKey, 116.374476, 39.911954, "store-xidan");
            System.out.println("[lab] 天安门-王府井 距离 → " +
                    lab.distance(labKey, "store-tiananmen", "store-wangfujing"));
            System.out.println("[lab] 附近 3km (用户在 116.40, 39.91)：");
            GeoResults<GeoLocation<String>> results =
                    lab.radiusWithArgs(labKey, 116.40, 39.91, 3.0, 10);
            for (GeoResult<GeoLocation<String>> r : results) {
                System.out.println("  - " + r.getContent().getName()
                        + " 距离=" + r.getDistance()
                        + " 坐标=" + r.getContent().getPoint());
            }
            template.delete(labKey);

            // ---------- 场景 1：附近门店 ----------
            L405NearbyStoreGeoScenario stores = new L405NearbyStoreGeoScenario(template);
            stores.addStore(city, "store-001", 116.397128, 39.916527);
            stores.addStore(city, "store-002", 116.418099, 39.917545);
            stores.addStore(city, "store-003", 116.374476, 39.911954);
            stores.addStore(city, "store-far",  117.000000, 40.000000); // 50km 外
            GeoResults<GeoLocation<String>> nearby =
                    stores.searchNearbyStores(city, 116.40, 39.91, 3.0, 5);
            System.out.println("[store] 北京附近 3km 门店数 → " + nearby.getContent().size());

            // ---------- 场景 2：附近车辆 ----------
            L405NearbyVehicleGeoScenario vehicles = new L405NearbyVehicleGeoScenario(template);
            vehicles.updateVehicleLocation(city, "veh-A", 116.40, 39.91);
            vehicles.updateVehicleLocation(city, "veh-B", 116.42, 39.93);
            // 同 vehicleId 覆盖位置
            vehicles.updateVehicleLocation(city, "veh-A", 116.41, 39.915);
            GeoResults<GeoLocation<String>> nearbyVehicles =
                    vehicles.searchNearbyVehicles(city, 116.40, 39.91, 5.0, 10);
            System.out.println("[vehicle] 附近车辆数 → " + nearbyVehicles.getContent().size());

            // ---------- 场景 3：距离计算 ----------
            L405StoreDistanceGeoScenario dist = new L405StoreDistanceGeoScenario(template);
            System.out.println("[distance] GEODIST store-001 ↔ store-002 → "
                    + dist.distance(city, "store-001", "store-002"));
            Point posA = dist.position(city, "store-001");
            Point posB = dist.position(city, "store-002");
            System.out.println("[distance] 应用层 Haversine → "
                    + L405StoreDistanceGeoScenario.haversineKm(
                            posA.getX(), posA.getY(), posB.getX(), posB.getY()) + " km");

            // ---------- 场景 4：附近召回 + 业务过滤 ----------
            L405NearbyStoreRecommendationScenario rec = new L405NearbyStoreRecommendationScenario(template);
            rec.registerStoreMeta("store-001", true,  10, 4.8);
            rec.registerStoreMeta("store-002", false, 5,  4.9); // 闭店
            rec.registerStoreMeta("store-003", true,  0,  4.5); // 缺货
            List<String> recommended = rec.recommendNearbyStores(city, 116.40, 39.91);
            System.out.println("[recommend] 附近 3km 营业中且有库存 → " + recommended);

            // 清理
            stores.removeStore(city, "store-001");
            stores.removeStore(city, "store-002");
            stores.removeStore(city, "store-003");
            stores.removeStore(city, "store-far");
            vehicles.removeVehicle(city, "veh-A");
            vehicles.removeVehicle(city, "veh-B");

            System.out.println("===== GEO Debug Done =====");
        }
    }
}
