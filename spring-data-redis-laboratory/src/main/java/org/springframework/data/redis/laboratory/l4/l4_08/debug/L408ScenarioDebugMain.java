package org.springframework.data.redis.laboratory.l4.l4_08.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.config.L408RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_08.scenario.L408AccountBalanceAntiPatternScenario;
import org.springframework.data.redis.laboratory.l4.l4_08.scenario.L408CouponClaimTransactionScenario;
import org.springframework.data.redis.laboratory.l4.l4_08.scenario.L408PointsExchangeTransactionScenario;
import org.springframework.data.redis.laboratory.l4.l4_08.scenario.L408StockDeductTransactionScenario;
import org.springframework.data.redis.laboratory.l4.l4_08.scenario.L408TaskRewardTransactionScenario;

/**
 * 业务场景调试主入口。串联 6 个事务场景：
 * <ol>
 *   <li>积分兑换（标准模板）</li>
 *   <li>账户余额扣减（反例）</li>
 *   <li>商品库存扣减</li>
 *   <li>优惠券领取（多 key WATCH）</li>
 *   <li>任务完成多缓存打包</li>
 *   <li>WATCH 冲突复现见 {@link L408ConflictDebugMain}</li>
 * </ol>
 */
public class L408ScenarioDebugMain {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(L408RedisConfig.class)) {

            StringRedisTemplate t = ctx.getBean(StringRedisTemplate.class);

            System.out.println("===== L4-08 Scenario Debug =====");

            // 1. 积分兑换
            L408PointsExchangeTransactionScenario points = new L408PointsExchangeTransactionScenario(t);
            String userId = "u-001";
            String orderId = "o-100";
            points.clearData(userId, orderId);
            points.initUserPoints(userId, 1000);
            System.out.println("[points] before = " + points.getUserPoints(userId));
            var pr = points.exchangeWithRetry(userId, orderId, 200, 3);
            System.out.println("[points] result = " + pr);
            System.out.println("[points] after = " + points.getUserPoints(userId));
            System.out.println("[points] order = " + points.getExchangeOrder(orderId));
            System.out.println("[points] metrics = " + points.getMetrics());
            points.clearData(userId, orderId);

            // 2. 账户余额（反例）
            L408AccountBalanceAntiPatternScenario bal = new L408AccountBalanceAntiPatternScenario(t);
            bal.initBalance("anti-u1", 500);
            System.out.println("[balance-anti] result = " + bal.unsafeRedisBalanceDeductExample("anti-u1", 100));
            System.out.println("[balance-anti] after = " + bal.getBalance("anti-u1"));
            bal.explainWhyNotRecommended();
            bal.dbTransactionPlaceholder();
            bal.clearData("anti-u1");

            // 3. 库存扣减
            L408StockDeductTransactionScenario stock = new L408StockDeductTransactionScenario(t);
            String sku = "sku-tx";
            stock.clearStock(sku);
            stock.initStock(sku, 10);
            for (int i = 0; i < 5; i++) {
                System.out.println("[stock] try i=" + i + " result = "
                        + stock.deductByWatchTransactionWithRetry(sku, 2, 3));
            }
            System.out.println("[stock] remain = " + stock.getStock(sku));
            System.out.println("[stock] metrics = " + stock.getMetrics());
            stock.clearStock(sku);

            // 4. 优惠券
            L408CouponClaimTransactionScenario coupon = new L408CouponClaimTransactionScenario(t);
            String act = "act-1";
            coupon.clearCoupon(act);
            coupon.initCouponStock(act, 3);
            for (int i = 1; i <= 5; i++) {
                String u = "u-" + i;
                System.out.println("[coupon] " + u + " result = "
                        + coupon.claimCouponWithRetry(act, u, 3)
                        + ", remain=" + coupon.getRemainStock(act));
            }
            System.out.println("[coupon] metrics = " + coupon.getMetrics());
            coupon.clearCoupon(act);

            // 5. 任务完成
            L408TaskRewardTransactionScenario task = new L408TaskRewardTransactionScenario(t);
            String date = "2026-05-04";
            task.clearTaskData("u-task", "task-1", date);
            System.out.println("[task] complete = " + task.completeTask("u-task", "task-1", 30, date));
            System.out.println("[task] status = " + task.getTaskStatus("u-task", "task-1"));
            System.out.println("[task] points = " + task.getUserPoints("u-task"));
            System.out.println("[task] top = " + task.getTopUsers(date, 5));
            task.clearTaskData("u-task", "task-1", date);

            System.out.println("===== Scenario Debug Done =====");
        }
    }
}
