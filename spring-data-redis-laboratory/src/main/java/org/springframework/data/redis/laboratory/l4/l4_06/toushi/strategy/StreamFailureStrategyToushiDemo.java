package org.springframework.data.redis.laboratory.l4.l4_06.toushi.strategy;

import org.springframework.data.redis.laboratory.l4.l4_06.domain.BizErrorType;

import java.util.Map;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 偷师 → Strategy
 *
 * 偷师对象：消费者面对失败时的"决策路径"，对应 SDR 的 ErrorHandler + ACK 选择。
 *
 * 它解决了什么问题：
 *  - 不同业务对失败的反应完全不同：
 *    库存中心抖动 → 重试；优惠券核销失败 → 立刻 DLQ；通知失败 → 弱重试 N 次；埋点失败 → 直接 ACK；
 *  - 把决策硬编码在每个消费者里，会出现"五个消费者写五份 if/else"；
 *  - 用策略接口把决策外置 → 同一个消费者骨架，注入不同 Strategy 实例。
 *
 * 在 C 端业务里如何采纳：
 *  - 任意"分类异常 → 决定动作"的场景：消息消费、外部调用、定时任务重跑、补偿流程；
 *  - 策略可按业务能力配；
 *  - 上线灰度时，可以从配置中心动态下发策略名。
 */
public class StreamFailureStrategyToushiDemo {

    public enum FailureAction {
        ACK_AND_SKIP,
        ACK_AND_DLQ,
        NO_ACK_RETRY_LATER,
        ACK_AND_ALERT
    }

    public interface FailureStrategy {
        FailureAction onFailure(BizErrorType errorType, int retryCount);
    }

    /** 库存：尽量重试；超过阈值 DLQ；参数非法直接 DLQ。 */
    public static class InventoryFailureStrategy implements FailureStrategy {
        private final int maxRetry;

        public InventoryFailureStrategy(int maxRetry) {
            this.maxRetry = maxRetry;
        }

        @Override
        public FailureAction onFailure(BizErrorType errorType, int retryCount) {
            if (errorType == BizErrorType.NON_RETRYABLE) return FailureAction.ACK_AND_DLQ;
            if (retryCount >= maxRetry) return FailureAction.ACK_AND_DLQ;
            return FailureAction.NO_ACK_RETRY_LATER;
        }
    }

    /** 优惠券：参数错误立刻 DLQ；其他可短暂重试。 */
    public static class CouponFailureStrategy implements FailureStrategy {
        @Override
        public FailureAction onFailure(BizErrorType errorType, int retryCount) {
            if (errorType == BizErrorType.NON_RETRYABLE) return FailureAction.ACK_AND_DLQ;
            if (retryCount >= 2) return FailureAction.ACK_AND_DLQ;
            return FailureAction.NO_ACK_RETRY_LATER;
        }
    }

    /** 通知：弱一致，重试 5 次后直接 ACK_AND_SKIP（用户体验损失一条短信可接受）。 */
    public static class NotifyFailureStrategy implements FailureStrategy {
        @Override
        public FailureAction onFailure(BizErrorType errorType, int retryCount) {
            if (retryCount >= 5) return FailureAction.ACK_AND_SKIP;
            return FailureAction.NO_ACK_RETRY_LATER;
        }
    }

    /** 埋点：任何失败都直接 ACK，业务无关性最高，避免拖累主消息流。 */
    public static class AnalyticsFailureStrategy implements FailureStrategy {
        @Override
        public FailureAction onFailure(BizErrorType errorType, int retryCount) {
            return FailureAction.ACK_AND_SKIP;
        }
    }

    /** 风控：失败必须告警，但消息照样 ACK，等人工介入。 */
    public static class RiskFailureStrategy implements FailureStrategy {
        @Override
        public FailureAction onFailure(BizErrorType errorType, int retryCount) {
            return FailureAction.ACK_AND_ALERT;
        }
    }

    public static void main(String[] args) {
        Map<String, FailureStrategy> registry = Map.of(
                "inventory", new InventoryFailureStrategy(5),
                "coupon", new CouponFailureStrategy(),
                "notify", new NotifyFailureStrategy(),
                "analytics", new AnalyticsFailureStrategy(),
                "risk", new RiskFailureStrategy()
        );

        // 模拟一个异常场景：参数非法 + 当前 retry=0
        for (Map.Entry<String, FailureStrategy> e : registry.entrySet()) {
            FailureAction a = e.getValue().onFailure(BizErrorType.NON_RETRYABLE, 0);
            System.out.printf("[toushi-strategy] %s.onFailure(NON_RETRYABLE,0) = %s%n", e.getKey(), a);
        }
        // 模拟一个抖动 + retry=4
        for (Map.Entry<String, FailureStrategy> e : registry.entrySet()) {
            FailureAction a = e.getValue().onFailure(BizErrorType.RETRYABLE, 4);
            System.out.printf("[toushi-strategy] %s.onFailure(RETRYABLE,4)     = %s%n", e.getKey(), a);
        }
    }
}
