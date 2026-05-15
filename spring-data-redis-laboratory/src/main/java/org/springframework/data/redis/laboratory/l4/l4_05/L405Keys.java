package org.springframework.data.redis.laboratory.l4.l4_05;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * L4-05 章节统一 key 前缀。
 * <p>
 * 本章 key 命名规范：
 * <pre>
 *   l4:05:hll:uv:home:{yyyyMMdd}
 *   l4:05:hll:uv:activity:{activityId}:{yyyyMMdd}
 *   l4:05:hll:uv:search:{keywordHash}:{yyyyMMdd}
 *   l4:05:bitmap:checkin:{userId}:{yyyyMM}
 *   l4:05:bitmap:active:daily:{yyyyMMdd}
 *   l4:05:bitmap:exposure:{featureId}:{yyyyMMdd}
 *   l4:05:geo:store:city:{cityCode}
 *   l4:05:geo:vehicle:city:{cityCode}
 * </pre>
 * 全部小写 + 冒号分段，避免特殊字符进 key。
 */
public final class L405Keys {

    public static final String PREFIX = "l4:05:";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private L405Keys() {
    }

    public static String homeUv(LocalDate date) {
        return PREFIX + "hll:uv:home:" + date.format(DAY);
    }

    public static String activityUv(String activityId, LocalDate date) {
        return PREFIX + "hll:uv:activity:" + activityId + ":" + date.format(DAY);
    }

    public static String searchKeywordUv(String keywordHash, LocalDate date) {
        return PREFIX + "hll:uv:search:" + keywordHash + ":" + date.format(DAY);
    }

    public static String monthlyCheckin(String userId, YearMonth yearMonth) {
        return PREFIX + "bitmap:checkin:" + userId + ":" + yearMonth.format(MONTH);
    }

    public static String dailyActive(LocalDate date) {
        return PREFIX + "bitmap:active:daily:" + date.format(DAY);
    }

    public static String featureExposure(String featureId, LocalDate date) {
        return PREFIX + "bitmap:exposure:" + featureId + ":" + date.format(DAY);
    }

    public static String storeGeo(String cityCode) {
        return PREFIX + "geo:store:city:" + cityCode;
    }

    public static String vehicleGeo(String cityCode) {
        return PREFIX + "geo:vehicle:city:" + cityCode;
    }

    public static String tempBitOpResult(String tag) {
        return PREFIX + "bitmap:tmp:" + tag;
    }

    public static String formatDay(LocalDate date) {
        return date.format(DAY);
    }
}
