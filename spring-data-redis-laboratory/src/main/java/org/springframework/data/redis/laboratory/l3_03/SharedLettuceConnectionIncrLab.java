package org.springframework.data.redis.laboratory.l3_03;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.springframework.data.redis.laboratory.util.RedisConfigUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * L3-03 实验①：共享连接 × 1000 线程并发 INCR
 * <p>
 * 【实验意图】
 * 让你亲眼看到："一条 StatefulRedisConnection + Netty Channel"
 * 是如何扛住 1000 个业务线程并发 INCR 的，且 Redis 端最终值精准等于 1000。
 * <p>
 * 【底层是怎么不出事的？】
 *   1. 1000 个业务线程并发调用 cmd.incr(KEY)
 *   2. Lettuce 把每次调用包装成 RedisCommand(含 CompletableFuture)
 *   3. CommandHandler 把 Command 入队、写入唯一 Netty Channel
 *   4. Netty EventLoop（单线程 / 极少线程）顺序写 Socket
 *   5. Redis 服务端单线程顺序处理，按顺序回包
 *   6. EventLoop 读到响应后，按 FIFO 从 stack 取出对应 Future complete
 *   7. 业务线程从 sync API 上唤醒，拿到自己那份结果
 * <p>
 * 【运行前提】
 *   保证 redis.properties 指向的 Redis 实例可用：
 *   docker run -d -p 6379:6379 redis:7
 * <p>
 * 【预期现象】
 *   - 期望值 = 1000，实际值 = 1000，异常 = 0
 *   - 总耗时通常 < 1s
 *   - 通过 netstat 观察：客户端 → Redis 仅 1 条 TCP 连接
 *
 * @author leiyuhang
 * @since 2026-04-26
 */
public class SharedLettuceConnectionIncrLab {

    private static final String KEY = "lab:l3_03:shared:counter";
    private static final int THREADS = 1000;

    public static void main(String[] args) throws Exception {
        RedisURI uri = buildRedisURI();
        RedisClient client = RedisClient.create(uri);

        // 全局只开一条连接，供所有业务线程共享 —— Lettuce 的标配姿势
        StatefulRedisConnection<String, String> connection = client.connect();
        RedisCommands<String, String> cmd = connection.sync();
        cmd.set(KEY, "0");

        AtomicInteger okCnt = new AtomicInteger();
        AtomicInteger errCnt = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            new Thread(() -> {
                try {
                    ready.countDown();
                    start.await();
                    cmd.incr(KEY);
                    okCnt.incrementAndGet();
                } catch (Exception ex) {
                    errCnt.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }, "biz-shared-" + i).start();
        }

        ready.await();
        long t0 = System.nanoTime();
        start.countDown();
        done.await();
        long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        System.out.println("═══════════════════════ L3-03 实验① 共享连接 × 1000 线程 ═══════════════════════");
        System.out.printf("线程数         = %d%n", THREADS);
        System.out.printf("成功次数       = %d%n", okCnt.get());
        System.out.printf("异常次数       = %d%n", errCnt.get());
        System.out.printf("Redis 最终值   = %s%n", cmd.get(KEY));
        System.out.printf("耗时           = %d ms%n", costMs);
        System.out.printf("连接对象类     = %s (instance=%s)%n",
                connection.getClass().getSimpleName(), System.identityHashCode(connection));
        System.out.println("══════════════════════════════════════════════════════════════════════════════");
        System.out.println("💡 结论：sync API 看似阻塞，底层却是 Netty EventLoop 串行写 Socket + 异步 Future 分发。");

        connection.close();
        client.shutdown();
    }

    private static RedisURI buildRedisURI() {
        RedisURI.Builder b = RedisURI.Builder
                .redis(RedisConfigUtils.getHost(), RedisConfigUtils.getPort())
                .withDatabase(RedisConfigUtils.getDatabase());
        String pwd = RedisConfigUtils.getPassword();
        if (pwd != null && !pwd.isEmpty()) {
            b.withPassword(pwd.toCharArray());
        }
        return b.build();
    }
}
