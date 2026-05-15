package org.springframework.data.redis.laboratory.l4.l4_05.toushi;

/**
 * 偷师 5：BoundOperations 资源绑定子门面。
 * <p>
 * RedisTemplate 的 boundXxxOps(key) 把 key 绑定一次后，每个调用都不再传 key——
 * 调用现场代码就像在操作一个"领域对象"。
 * <p>
 * 茶饮 C 端典型可绑定资源：
 * <ul>
 *   <li>{@code DailyUvOperations.bind(date)} → 当日 UV 篮子</li>
 *   <li>{@code MonthlyCheckinOperations.bind(userId, yearMonth)} → 用户某月签到日历</li>
 *   <li>{@code CityGeoOperations.bind(cityCode)} → 城市门店分布图</li>
 * </ul>
 */
public class BoundResourceOperationsDemo {

    /* ---------- DailyUv ---------- */
    public interface DailyUvOperations {
        BoundDailyUvOperations bind(String date);
        void recordVisit(String date, String userId);
        long uv(String date);
    }
    public interface BoundDailyUvOperations {
        void recordVisit(String userId);
        long uv();
    }
    public static class DefaultDailyUvOperations implements DailyUvOperations {
        @Override public BoundDailyUvOperations bind(String date) {
            return new BoundDailyUvOperations() {
                @Override public void recordVisit(String u) { DefaultDailyUvOperations.this.recordVisit(date, u); }
                @Override public long uv() { return DefaultDailyUvOperations.this.uv(date); }
            };
        }
        @Override public void recordVisit(String date, String u) { /* PFADD ... */ }
        @Override public long uv(String date) { return 0; }
    }

    /* ---------- MonthlyCheckin ---------- */
    public interface MonthlyCheckinOperations {
        BoundMonthlyCheckinOperations bind(String userId, String yearMonth);
        void check(String userId, String yearMonth, int day);
        boolean isChecked(String userId, String yearMonth, int day);
    }
    public interface BoundMonthlyCheckinOperations {
        void check(int day);
        boolean isChecked(int day);
        long countCheckedDays();
    }
    public static class DefaultMonthlyCheckinOperations implements MonthlyCheckinOperations {
        @Override public BoundMonthlyCheckinOperations bind(String userId, String yearMonth) {
            return new BoundMonthlyCheckinOperations() {
                @Override public void check(int day) { DefaultMonthlyCheckinOperations.this.check(userId, yearMonth, day); }
                @Override public boolean isChecked(int day) {
                    return DefaultMonthlyCheckinOperations.this.isChecked(userId, yearMonth, day);
                }
                @Override public long countCheckedDays() { return 0; /* BITCOUNT */ }
            };
        }
        @Override public void check(String userId, String yearMonth, int day) { /* setBit */ }
        @Override public boolean isChecked(String userId, String yearMonth, int day) { return false; }
    }

    /* ---------- CityGeo ---------- */
    public interface CityGeoOperations {
        BoundCityGeoOperations bind(String cityCode);
        void addStore(String cityCode, String storeId, double lon, double lat);
    }
    public interface BoundCityGeoOperations {
        void addStore(String storeId, double lon, double lat);
        java.util.List<String> nearby(double lon, double lat, double radiusKm, int limit);
    }
    public static class DefaultCityGeoOperations implements CityGeoOperations {
        @Override public BoundCityGeoOperations bind(String cityCode) {
            return new BoundCityGeoOperations() {
                @Override public void addStore(String id, double lon, double lat) {
                    DefaultCityGeoOperations.this.addStore(cityCode, id, lon, lat);
                }
                @Override public java.util.List<String> nearby(double lon, double lat, double r, int n) {
                    return java.util.List.of();
                }
            };
        }
        @Override public void addStore(String cityCode, String storeId, double lon, double lat) { /* GEOADD */ }
    }

    public static void main(String[] args) {
        BoundDailyUvOperations todayUv = new DefaultDailyUvOperations().bind("20260501");
        todayUv.recordVisit("u-1");
        todayUv.recordVisit("u-2");
        System.out.println("BoundResourceOperationsDemo: 今日 UV = " + todayUv.uv());

        BoundMonthlyCheckinOperations userCheckin =
                new DefaultMonthlyCheckinOperations().bind("u-1001", "202605");
        userCheckin.check(1);
        userCheckin.check(2);
        System.out.println("BoundResourceOperationsDemo: 1 号签到? " + userCheckin.isChecked(1));

        BoundCityGeoOperations bjStores = new DefaultCityGeoOperations().bind("BJ");
        bjStores.addStore("store-001", 116.4, 39.9);
        System.out.println("BoundResourceOperationsDemo: 附近门店 → "
                + bjStores.nearby(116.4, 39.9, 3, 5));
    }
}
