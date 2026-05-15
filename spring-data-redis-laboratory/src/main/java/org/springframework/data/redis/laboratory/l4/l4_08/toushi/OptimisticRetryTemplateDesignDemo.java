package org.springframework.data.redis.laboratory.l4.l4_08.toushi;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 偷师：WATCH 乐观锁 → 抽象成可复用的 OptimisticRetryTemplate。
 * <p>
 * 业务背后的统一模式：<b>读 → 计算 → 提交（带版本检查） → 失败重试</b>。
 * 你写过的所有"WATCH key + 改值 + EXEC"、CAS 指令、ETag、DB 乐观锁列，
 * 本质是同一个心智模型。
 */
public class OptimisticRetryTemplateDesignDemo {

    /** 模拟一个被并发修改的资源（带版本号）。 */
    public static final class VersionedResource {
        private int value;
        private int version;
        public synchronized int read(int[] outVersion) {
            outVersion[0] = version;
            return value;
        }
        public synchronized boolean compareAndSet(int expectedVersion, int newValue) {
            if (version != expectedVersion) return false;
            value = newValue;
            version++;
            return true;
        }
        public synchronized int snapshot() { return value; }
    }

    @FunctionalInterface
    public interface OptimisticAction<T> {
        /**
         * @return 业务结果；返回 null 表示"提交时检测到冲突"，触发重试。
         */
        T tryOnce();
    }

    public static final class OptimisticRetryTemplate {
        public <T> T executeWithRetry(int maxRetries, OptimisticAction<T> action) {
            int attempts = 0;
            while (true) {
                attempts++;
                T r = action.tryOnce();
                if (r != null) {
                    System.out.println("[retry] success after attempts=" + attempts);
                    return r;
                }
                if (attempts > maxRetries) {
                    System.out.println("[retry] give up after attempts=" + attempts);
                    return null;
                }
                long jitter = ThreadLocalRandom.current().nextLong(5);
                try { Thread.sleep(jitter); } catch (InterruptedException ignored) {}
            }
        }
    }

    public static void main(String[] args) {
        VersionedResource res = new VersionedResource();
        AtomicInteger noisyOther = new AtomicInteger();

        Thread other = new Thread(() -> {
            // 模拟"其他客户端"持续干扰
            for (int i = 0; i < 3; i++) {
                int[] v = new int[1];
                int curr = res.read(v);
                res.compareAndSet(v[0], curr + 1);
                noisyOther.incrementAndGet();
                try { Thread.sleep(2); } catch (InterruptedException ignored) {}
            }
        }, "noisy-other");
        other.setDaemon(true);
        other.start();

        OptimisticRetryTemplate template = new OptimisticRetryTemplate();
        Integer r = template.executeWithRetry(5, () -> {
            int[] v = new int[1];
            int curr = res.read(v);
            // 模拟业务"读后判断写"的耗时
            try { Thread.sleep(3); } catch (InterruptedException ignored) {}
            int newVal = curr + 100;
            return res.compareAndSet(v[0], newVal) ? newVal : null;
        });

        System.out.println("[demo] retry result = " + r
                + ", final value = " + res.snapshot()
                + ", noisy writes = " + noisyOther.get());
    }
}
