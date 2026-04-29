package org.springframework.data.redis.laboratory.l3_02;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.springframework.data.redis.laboratory.util.RedisConfigUtils;

import java.util.concurrent.CountDownLatch;

/**
 * L3-02 B —— Lettuce 单连接 × 10 线程 INCR = 零冲突
 * <p>
 * 【实验意图】
 * 把 L302A 的"10 线程共享 1 实例"场景原封不动搬到 Lettuce 上，
 * 验证 StatefulRedisConnection 的线程安全承诺：
 * <p>
 * 【为什么这段代码绝对安全？】
 *   1. 10 个业务线程并发调 cmd.incr(KEY)
 *   2. 每个调用被 Lettuce 包装成 RedisCommand(含 CompletableFuture)，进入 CommandHandler.stack 队列
 *   3. 真正写 Socket 的只有一个 Netty EventLoop 线程 —— 字节流严格串行，不会交错
 *   4. Redis 是单线程顺序执行，响应按请求顺序返回
 *   5. EventLoop 拿到响应后，按 FIFO 从 stack 取出对应的 Future，complete 之
 *   6. 10 个业务线程各自被唤醒，拿到自己那份结果
 * <p>
 * 【运行前提】
 *   docker run -d -p 6379:6379 redis:7
 * <p>
 * 【预期输出】
 *   期望值 = 2000, 实际值 = 2000, 异常 = 0, 耗时 ≈ 100~200ms
 * <p>
 * 【实验彩蛋】
 * 把 cmd.incr(KEY) 换成 cmd.blpop(0, "lab:l302:empty:queue")，
 * 你会立刻看到"一个阻塞命令堵死整条马路"的生产故事根因。
 */
public class L302B_LettuceSharedConnectionLab {

    private static final String KEY = "lab:l302:lettuce:counter";
    private static final int THREADS = 10;
    private static final int LOOPS_PER_THREAD = 200;

    public static void main(String[] args) throws Exception {

        // ═══════════════════════════════════════════════════════════
        // 构造 RedisURI（对应 application.yml 里的 spring.redis.*）
        // ═══════════════════════════════════════════════════════════
        RedisURI.Builder uriBuilder = RedisURI.Builder
                .redis(RedisConfigUtils.getHost(), RedisConfigUtils.getPort())
                .withDatabase(RedisConfigUtils.getDatabase());
        String password = RedisConfigUtils.getPassword();
        if (password != null && !password.isEmpty()) {
            uriBuilder.withPassword(password.toCharArray());
        }
        RedisURI uri = uriBuilder.build();

        RedisClient client = RedisClient.create(uri);

        // ═══════════════════════════════════════════════════════════
        // 全局只开一条连接，10 线程共享 —— Lettuce 的经典姿势
        // ═══════════════════════════════════════════════════════════
        StatefulRedisConnection<String, String> connection = client.connect();
        RedisCommands<String, String> cmd = connection.sync();
        cmd.set(KEY, "0");

        CountDownLatch latch = new CountDownLatch(THREADS);
        long start = System.currentTimeMillis();

        for (int i = 0; i < THREADS; i++) {
            new Thread(() -> {
                for (int j = 0; j < LOOPS_PER_THREAD; j++) {
                    cmd.blpop(0, "lab:l302:empty:queue");
                    //cmd.incr(KEY);  // 全程共享同一个 cmd 对象，完全无锁
                }
                latch.countDown();
            }, "biz-lettuce-" + i).start();
        }

        latch.await();
        long cost = System.currentTimeMillis() - start;

        System.out.println("═══════════════════════════════════════════════");
        System.out.printf("期望值 = %d%n", THREADS * LOOPS_PER_THREAD);
        System.out.printf("实际值 = %s%n", cmd.get(KEY));
        System.out.printf("耗时   = %d ms%n", cost);
        System.out.printf("原生连接类型 = %s%n", connection.getClass().getSimpleName());
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("💡 结论：多线程共享一条 Lettuce 连接，EventLoop 单线程写 Socket + FIFO 响应分发，天然线程安全。");

        connection.close();
        client.shutdown();
    }
}
