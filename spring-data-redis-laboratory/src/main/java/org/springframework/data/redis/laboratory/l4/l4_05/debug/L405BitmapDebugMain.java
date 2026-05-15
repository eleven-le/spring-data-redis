package org.springframework.data.redis.laboratory.l4.l4_05.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_05.bitmap.L405BitmapBasicOperationsLab;
import org.springframework.data.redis.laboratory.l4.l4_05.bitmap.L405DailyActiveBitmapScenario;
import org.springframework.data.redis.laboratory.l4.l4_05.bitmap.L405FeatureExposureBitmapScenario;
import org.springframework.data.redis.laboratory.l4.l4_05.bitmap.L405RetentionBitmapScenario;
import org.springframework.data.redis.laboratory.l4.l4_05.bitmap.L405UserMonthlyCheckinBitmapScenario;
import org.springframework.data.redis.laboratory.l4.l4_05.config.L405RedisConfig;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Bitmap 调试入口。
 * <p>
 * <b>建议断点位置</b>：
 * <ol>
 *   <li>{@code RedisTemplate#opsForValue} —— 看 ValueOperations 子门面</li>
 *   <li>{@code DefaultValueOperations#setBit} —— 看 setBit/getBit 走 RedisCallback</li>
 *   <li>{@code DefaultValueOperations#getBit}</li>
 *   <li>{@code RedisTemplate#execute(RedisCallback)}</li>
 *   <li>{@code RedisConnectionUtils#doGetConnection}</li>
 *   <li>{@code LettuceConnection#stringCommands} —— bitCount / bitOp 都走 stringCommands()</li>
 * </ol>
 */
public class L405BitmapDebugMain {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(L405RedisConfig.class)) {

            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);

            System.out.println("===== L4-05 Bitmap Debug =====");

            // ---------- 基础实验 ----------
            L405BitmapBasicOperationsLab lab = new L405BitmapBasicOperationsLab(template);
            String labKey = "l4:05:bitmap:lab:demo";
            template.delete(labKey);
            lab.setBit(labKey, 0, true);
            lab.setBit(labKey, 7, true);
            lab.setBit(labKey, 100, true);
            System.out.println("[lab] getBit(7) → " + lab.getBit(labKey, 7));
            System.out.println("[lab] bitCount → " + lab.bitCount(labKey));
            template.delete(labKey);

            // ---------- 场景 1：月度签到 ----------
            L405UserMonthlyCheckinBitmapScenario checkin = new L405UserMonthlyCheckinBitmapScenario(template);
            String userId = "u-1001";
            YearMonth ym = YearMonth.now();
            checkin.clearMonthlyCheckin(userId, ym);
            for (int day = 1; day <= 5; day++) {
                checkin.checkin(userId, ym.atDay(day));
            }
            System.out.println("[checkin] 1 号签到? → " + checkin.hasCheckedIn(userId, ym.atDay(1)));
            System.out.println("[checkin] 6 号签到? → " + checkin.hasCheckedIn(userId, ym.atDay(6)));
            System.out.println("[checkin] 当月签到天数 → " + checkin.countMonthlyCheckin(userId, ym));

            // ---------- 场景 2：DAU ----------
            L405DailyActiveBitmapScenario dau = new L405DailyActiveBitmapScenario(template);
            LocalDate today = LocalDate.now();
            dau.clearDailyActive(today);
            for (long idx = 0; idx < 10_000; idx++) {
                dau.markActive(idx, today);
            }
            System.out.println("[dau] 今日活跃 (期望 10000) → " + dau.countDailyActive(today));

            // ---------- 场景 3：连续活跃留存 ----------
            L405RetentionBitmapScenario retention = new L405RetentionBitmapScenario(template);
            LocalDate base = LocalDate.now().minusDays(2);
            // 准备 3 天数据
            for (int d = 0; d < 3; d++) {
                LocalDate day = base.plusDays(d);
                dau.clearDailyActive(day);
                // 0..999 全部活跃
                for (long idx = 0; idx < 1000; idx++) {
                    dau.markActive(idx, day);
                }
                // 1000..1499 只活跃当天
                for (long idx = 1000; idx < 1500; idx++) {
                    if (idx % 3 == d) {
                        dau.markActive(idx, day);
                    }
                }
            }
            long continuous = retention.countContinuousActive(base, 3);
            long anyActive = retention.countAnyActive(base, 3);
            System.out.println("[retention] 连续 3 天活跃 (期望 1000) → " + continuous);
            System.out.println("[retention] 至少 1 天活跃 → " + anyActive);

            // ---------- 场景 4：曝光 ----------
            L405FeatureExposureBitmapScenario exp = new L405FeatureExposureBitmapScenario(template);
            String featureId = "guide-newbie-popup";
            exp.clearExposure(featureId, today);
            exp.markExposed(featureId, 1L, today);
            exp.markExposed(featureId, 2L, today);
            System.out.println("[exposure] user1 已曝光? → " + exp.hasExposed(featureId, 1L, today));
            System.out.println("[exposure] user3 已曝光? → " + exp.hasExposed(featureId, 3L, today));
            System.out.println("[exposure] 曝光人数 → " + exp.countExposure(featureId, today));

            System.out.println("===== Bitmap Debug Done =====");
        }
    }
}
