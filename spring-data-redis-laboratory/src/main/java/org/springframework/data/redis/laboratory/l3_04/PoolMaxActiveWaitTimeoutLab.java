package org.springframework.data.redis.laboratory.l3_04;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实验②：max-active 打满 + max-wait 超时。
 *
 * <p>这就是生产里"接口大面积超时但 Redis 看着没事"的最小复现：
 * 5 个业务线程同时来要连接，池上限只有 2，前 2 个把连接捏在手里 3 秒，
 * 后 3 个在 maxWait=1s 内拿不到，全部抛 NoSuchElementException("Timeout waiting ...")。
 *
 * <p>预期现象：
 * <pre>
 *   [t0/t1] borrowed in xxx ms ... 持有 3 秒
 *   [t2/t3/t4] BORROW FAILED after ~1000ms : NoSuchElementException Timeout waiting for idle object
 *   final: ok=2, timeout=3
 * </pre>
 */
public class PoolMaxActiveWaitTimeoutLab {

    public static void main(String[] args) throws InterruptedException {
        GenericObjectPoolConfig<MockRedisConnection> cfg = new GenericObjectPoolConfig<>();
        cfg.setMaxTotal(2);                                        // max-active = 2
        cfg.setMaxWait(Duration.ofMillis(1000));                   // max-wait = 1s
        cfg.setBlockWhenExhausted(true);
        cfg.setJmxEnabled(false);

        try (GenericObjectPool<MockRedisConnection> pool =
                     new GenericObjectPool<>(new MockRedisConnectionFactory(), cfg)) {

            int n = 5;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);
            AtomicInteger ok = new AtomicInteger();
            AtomicInteger timeout = new AtomicInteger();

            for (int i = 0; i < n; i++) {
                final String tag = "t" + i;
                new Thread(() -> {
                    try {
                        start.await();
                        long t0 = System.currentTimeMillis();
                        MockRedisConnection conn = pool.borrowObject();
                        long cost = System.currentTimeMillis() - t0;
                        System.out.println("[" + tag + "] borrowed " + conn + " in " + cost + "ms");
                        try {
                            Thread.sleep(3000);                    // 故意持有 3s，模拟"慢 Lua / 大 key 返回"
                        } finally {
                            pool.returnObject(conn);
                            System.out.println("[" + tag + "] returned");
                            ok.incrementAndGet();
                        }
                    } catch (Exception e) {
                        long cost = System.currentTimeMillis() % 100000L;
                        System.out.println("[" + tag + "] BORROW FAILED @t=" + cost + " : " + e);
                        timeout.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                }, tag).start();
            }

            start.countDown();
            done.await();

            System.out.println("\n========= 结果汇总 =========");
            System.out.println("ok      = " + ok.get() + "   ← 实际拿到连接的线程");
            System.out.println("timeout = " + timeout.get() + "   ← max-wait 内没拿到，立刻失败");
            System.out.println("活动连接 / 空闲连接 = " + pool.getNumActive() + " / " + pool.getNumIdle());
        }
    }
}
