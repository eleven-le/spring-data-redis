package org.springframework.data.redis.laboratory.l3_02;

import org.springframework.data.redis.laboratory.util.RedisConfigUtils;
import redis.clients.jedis.Jedis;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * L3-02 A —— Jedis 多线程共享实例 = 协议错乱现场
 * <p>
 * 【实验意图】
 * 故意违反 Jedis "一线程一实例" 的契约，看看协议层错乱长什么样。
 * 让"非线程安全"四个字从口号变成你亲眼见过的异常栈。
 * <p>
 * 【为什么必然会错？】
 * Jedis 底层就是 java.net.Socket + 阻塞 InputStream / OutputStream。
 * 两个线程同时往同一个 OutputStream 写 RESP 协议字节，字节流必然交错：
 *   T1:  *3\r\n$3\r\nSE
 *   T2:        *2\r\n$3\r\nGET\r\n
 *   Socket 实际发出: *3\r\n$3\r\nSE*2\r\n$3\r\nGET\r\n   ← Redis 端直接解析失败
 * <p>
 * 【运行前提】
 *   docker run -d -p 6379:6379 redis:7
 * <p>
 * 【预期现象】（每次跑结果不完全一样，但必定出现至少一种异常）
 *   - JedisDataException: ERR Protocol error: expected '$', got '*'
 *   - JedisConnectionException: Unexpected end of stream.
 *   - ClassCastException: String cannot be cast to Long
 *   - 最终计数器值 ≠ 期望的 2000
 */
public class L302A_JedisSharedInstanceCrashLab {

    private static final String KEY = "lab:l302:jedis:counter";
    private static final int THREADS = 10;
    private static final int LOOPS_PER_THREAD = 200;

    public static void main(String[] args) throws Exception {

        // ═══════════════════════════════════════════════════════════
        // 全局只开 1 个 Jedis 实例 = 1 条 Socket，故意让多线程去抢
        // ═══════════════════════════════════════════════════════════
        Jedis shared = new Jedis(RedisConfigUtils.getHost(), RedisConfigUtils.getPort());
        String password = RedisConfigUtils.getPassword();
        if (password != null && !password.isEmpty()) {
            shared.auth(password);
        }
        shared.select(RedisConfigUtils.getDatabase());
        shared.set(KEY, "0");

        AtomicInteger errors = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(THREADS);
        long start = System.currentTimeMillis();

        for (int i = 0; i < THREADS; i++) {
            final int tid = i;
            new Thread(() -> {
                try {
                    for (int j = 0; j < LOOPS_PER_THREAD; j++) {
                        // 👇 故意不加锁，模拟菜鸟把 Jedis 当单例 Bean 用
                        shared.incr(KEY);
                    }
                } catch (Throwable t) {
                    errors.incrementAndGet();
                    System.err.printf("[T%02d] 炸了 → %s: %s%n",
                            tid, t.getClass().getSimpleName(), t.getMessage());
                } finally {
                    latch.countDown();
                }
            }, "biz-jedis-" + i).start();
        }

        latch.await();
        long cost = System.currentTimeMillis() - start;

        String finalVal;
        try {
            finalVal = shared.get(KEY);
        } catch (Throwable t) {
            finalVal = "【读取失败，连接已污染】" + t.getClass().getSimpleName();
        }

        System.out.println("═══════════════════════════════════════════════");
        System.out.printf("期望值      = %d%n", THREADS * LOOPS_PER_THREAD);
        System.out.printf("实际值      = %s%n", finalVal);
        System.out.printf("异常线程数  = %d / %d%n", errors.get(), THREADS);
        System.out.printf("总耗时      = %d ms%n", cost);
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("💡 结论：Jedis 非线程安全 = 协议层面的根本冲突，不是偶尔出错，是绝对出错。");

        try {
            shared.close();
        } catch (Exception ignore) {
            // 连接已污染，close 也可能报错，忽略
        }
    }
}
