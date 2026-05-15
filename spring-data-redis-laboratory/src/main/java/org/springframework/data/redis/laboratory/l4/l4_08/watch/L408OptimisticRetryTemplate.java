package org.springframework.data.redis.laboratory.l4.l4_08.watch;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WATCH 乐观重试模板。
 * <p>
 * 业务诉求：一段事务逻辑用 WATCH 保护，可能因为并发被 EXEC 取消。
 * 我们封装"最大重试次数 + 指数退避 + jitter + 冲突指标统计"，让业务侧只关心
 * action 本身的幂等实现。
 * <p>
 * <b>重要约束</b>：
 * <ul>
 *   <li>action 必须幂等：因为重试会重新执行整段事务逻辑；</li>
 *   <li>重试上限不能"无限大"——高冲突下要降级而不是死循环；</li>
 *   <li>建议 baseBackoff 在 5~30ms，结合业务实测调优。</li>
 * </ul>
 */
public class L408OptimisticRetryTemplate {

    /** 业务回调。 */
    @FunctionalInterface
    public interface OptimisticAction<T> {
        /**
         * @return 业务结果；返回 null 表示本次需要重试（事务被 EXEC 取消）。
         */
        T tryOnce() throws Exception;
    }

    /** 业务结果包装：成功值 + 实际尝试次数 + 是否最终成功。 */
    public static final class OptimisticResult<T> {
        private final T value;
        private final int attempts;
        private final boolean success;

        public OptimisticResult(T value, int attempts, boolean success) {
            this.value = value;
            this.attempts = attempts;
            this.success = success;
        }
        public T getValue() { return value; }
        public int getAttempts() { return attempts; }
        public boolean isSuccess() { return success; }

        @Override
        public String toString() {
            return "OptimisticResult{success=" + success + ", attempts=" + attempts + ", value=" + value + "}";
        }
    }

    /** 冲突指标。线程安全；多场景共用一个 metrics 也可。 */
    public static final class RetryMetrics {
        private final AtomicLong totalCalls = new AtomicLong();
        private final AtomicLong totalRetries = new AtomicLong();
        private final AtomicLong successCount = new AtomicLong();
        private final AtomicLong failureCount = new AtomicLong();

        public long getTotalCalls() { return totalCalls.get(); }
        public long getTotalRetries() { return totalRetries.get(); }
        public long getSuccessCount() { return successCount.get(); }
        public long getFailureCount() { return failureCount.get(); }

        public double conflictRate() {
            long calls = totalCalls.get();
            return calls == 0 ? 0.0 : (double) totalRetries.get() / calls;
        }

        @Override
        public String toString() {
            return "RetryMetrics{calls=" + totalCalls.get()
                    + ", retries=" + totalRetries.get()
                    + ", success=" + successCount.get()
                    + ", failure=" + failureCount.get()
                    + ", conflictRate=" + String.format("%.4f", conflictRate()) + "}";
        }
    }

    private final RetryMetrics metrics = new RetryMetrics();

    public RetryMetrics getMetrics() {
        return metrics;
    }

    /**
     * 不带退避的重试：仅适合单元测试 / 低 QPS demo。
     */
    public <T> OptimisticResult<T> executeWithRetry(int maxRetries, OptimisticAction<T> action) {
        return executeWithBackoff(maxRetries, Duration.ZERO, action);
    }

    /**
     * 带退避的重试：base + jitter，避免雷同节奏的"重试雪崩"。
     *
     * @param maxRetries 最大重试次数（不含首次执行）。例如 3 = 总共 4 次。
     * @param baseBackoff 退避基线，例如 10ms。jitter ∈ [0, base)。
     */
    public <T> OptimisticResult<T> executeWithBackoff(int maxRetries,
                                                      Duration baseBackoff,
                                                      OptimisticAction<T> action) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries 不能为负");
        }
        metrics.totalCalls.incrementAndGet();

        int attempts = 0;
        while (true) {
            attempts++;
            T result;
            try {
                result = action.tryOnce();
            } catch (Exception e) {
                // 业务异常不视为冲突，直接抛出由调用方处理
                metrics.failureCount.incrementAndGet();
                throw new RuntimeException("OptimisticAction failed at attempt=" + attempts, e);
            }

            if (result != null) {
                metrics.successCount.incrementAndGet();
                return new OptimisticResult<>(result, attempts, true);
            }

            // 冲突
            if (attempts > maxRetries) {
                metrics.failureCount.incrementAndGet();
                return new OptimisticResult<>(null, attempts, false);
            }
            metrics.totalRetries.incrementAndGet();
            sleepBackoff(baseBackoff, attempts);
        }
    }

    private static void sleepBackoff(Duration base, int attempt) {
        long baseMs = base.toMillis();
        if (baseMs <= 0) return;
        // 指数退避 + jitter：base * 2^(attempt-1) + rand[0, base)
        long backoff = baseMs * (1L << Math.min(attempt - 1, 6));
        long jitter = ThreadLocalRandom.current().nextLong(baseMs);
        try {
            Thread.sleep(backoff + jitter);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("backoff interrupted", ie);
        }
    }
}
