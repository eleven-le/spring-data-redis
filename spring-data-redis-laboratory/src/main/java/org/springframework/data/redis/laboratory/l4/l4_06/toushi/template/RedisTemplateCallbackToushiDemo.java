package org.springframework.data.redis.laboratory.l4.l4_06.toushi.template;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 偷师 → Template Method + Callback
 *
 * 偷师对象：{@code RedisTemplate#execute(RedisCallback, exposeConnection, pipeline)}
 *
 * 它解决了什么问题：
 *  - 业务侧不应该自己拿连接、自己 try-finally 释放、自己处理异常翻译；
 *  - 把"获取上下文 / 计时 / 异常翻译 / 回调 / 释放上下文"封死在模板里；
 *  - 业务回调只关心一件事：拿到 ctx 后做什么。
 *
 * 在 C 端业务里如何采纳：
 *  - 任意"调外部依赖"的封装：库存中心 / 优惠券中心 / 风控引擎 / 三方支付查询；
 *  - 模板负责：取上下文（traceId / clientName）→ 打 metric → 熔断判定 → 调业务回调 → 异常翻译 → 释放 ctx。
 *
 * 这里给出一个"领域无关"的 Template + Callback 骨架，验证模式可以脱离 Redis 工作。
 */
public class RedisTemplateCallbackToushiDemo {

    /** 抽象上下文，模拟 RedisConnection 的位置。 */
    public static class CallContext {
        public final String traceId;
        public final String dependency;
        public final Instant startedAt;

        public CallContext(String traceId, String dependency) {
            this.traceId = traceId;
            this.dependency = dependency;
            this.startedAt = Instant.now();
        }
    }

    /** 业务回调，对应 RedisCallback。 */
    @FunctionalInterface
    public interface CallCallback<T> {
        T doInCall(CallContext ctx) throws Exception;
    }

    /** 模板基类。子类只决定"怎么取上下文"。 */
    public static abstract class AbstractCallTemplate {

        protected abstract CallContext openContext(String dependency);

        protected void closeContext(CallContext ctx) {
            // 模拟 connection.close / 资源归还。
        }

        public final <T> T execute(String dependency, CallCallback<T> callback) {
            CallContext ctx = openContext(dependency);
            boolean closed = false;
            try {
                T result = callback.doInCall(ctx);
                closeContext(ctx);
                closed = true;
                logSuccess(ctx);
                return result;
            } catch (Exception ex) {
                logFailure(ctx, ex);
                throw translate(ex);
            } finally {
                if (!closed) {
                    closeContext(ctx);
                }
            }
        }

        private static RuntimeException translate(Exception ex) {
            if (ex instanceof RuntimeException re) return re;
            return new RuntimeException("dependency call failed", ex);
        }

        private static void logSuccess(CallContext ctx) {
            Duration cost = Duration.between(ctx.startedAt, Instant.now());
            System.out.printf("[toushi-template] OK dep=%s trace=%s cost=%dms%n",
                    ctx.dependency, ctx.traceId, cost.toMillis());
        }

        private static void logFailure(CallContext ctx, Exception ex) {
            Duration cost = Duration.between(ctx.startedAt, Instant.now());
            System.err.printf("[toushi-template] FAIL dep=%s trace=%s cost=%dms ex=%s%n",
                    ctx.dependency, ctx.traceId, cost.toMillis(), ex.toString());
        }
    }

    /** 一个具体模板：模拟"调三方支付查询"。 */
    public static class PaymentCallTemplate extends AbstractCallTemplate {
        @Override
        protected CallContext openContext(String dependency) {
            return new CallContext(UUID.randomUUID().toString(), dependency);
        }
    }

    /** 直接 main，不依赖 Spring，演示模式本身。 */
    public static void main(String[] args) {
        PaymentCallTemplate template = new PaymentCallTemplate();
        String result = template.execute("payment-center", ctx -> {
            // 业务回调里只关心"做什么"。
            Thread.sleep(40);
            return "PAY_OK trace=" + ctx.traceId;
        });
        System.out.println("[toushi-template] result=" + result);

        try {
            template.execute("payment-center", ctx -> {
                throw new IllegalStateException("simulated downstream timeout");
            });
        } catch (Exception ex) {
            System.out.println("[toushi-template] caught translated: " + ex.getMessage());
        }
    }
}
