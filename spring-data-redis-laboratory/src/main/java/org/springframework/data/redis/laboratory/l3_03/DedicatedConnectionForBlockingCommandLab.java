package org.springframework.data.redis.laboratory.l3_03;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.springframework.data.redis.laboratory.util.RedisConfigUtils;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * L3-03 实验③：用专用连接隔离阻塞命令
 * <p>
 * 【实验意图】
 * 把实验②的"BLPOP 投毒"放到一条独立连接上，
 * 让普通 INCR 线程使用另一条共享连接，验证：
 * 阻塞命令一旦"自己开一条专用连接"，就再也污染不到普通命令了。
 * <p>
 * 这就是 Spring Data Redis 在 LettuceConnection 内部
 * 看到 MULTI/SUBSCRIBE/BLPOP 等命令时，会切换到 asyncDedicatedConnection 的根本原因。
 * <p>
 * 【运行前提】
 *   docker run -d -p 6379:6379 redis:7
 * <p>
 * 【预期现象】
 *   - dedicatedConn 上 BLPOP 永久阻塞（不影响别人）
 *   - sharedConn 上 INCR 全部成功
 *   - okCnt = THREADS, timeoutCnt = 0
 *
 * @author leiyuhang
 * @since 2026-04-26
 */
public class DedicatedConnectionForBlockingCommandLab {

    private static final String COUNTER_KEY = "lab:l3_03:dedicated:counter";
    private static final String EMPTY_QUEUE = "lab:l3_03:dedicated:nonexistent:queue";
    private static final int THREADS = 50;

    public static void main(String[] args) throws Exception {

        RedisURI uri = buildRedisURI();
        RedisClient client = RedisClient.create(uri);

        // ──────── 关键：两条连接，各司其职 ────────
        StatefulRedisConnection<String, String> sharedConn = client.connect();    // 普通命令用
        StatefulRedisConnection<String, String> dedicatedConn = client.connect(); // 阻塞命令独占
        sharedConn.setTimeout(Duration.ofSeconds(2));
        dedicatedConn.setTimeout(Duration.ofHours(1)); // 专用连接允许长阻塞

        RedisCommands<String, String> sharedCmd = sharedConn.sync();
        RedisCommands<String, String> dedicatedCmd = dedicatedConn.sync();

        sharedCmd.del(EMPTY_QUEUE);
        sharedCmd.set(COUNTER_KEY, "0");

        AtomicInteger okCnt = new AtomicInteger();
        AtomicInteger timeoutCnt = new AtomicInteger();

        // 投毒线程跑在 dedicatedConn 上 —— 你想阻塞多久阻塞多久
        Thread poisoner = new Thread(() -> {
            try {
                System.out.println("[poisoner] 在 *专用连接* 上 BLPOP 0 ...（隔离区）");
                dedicatedCmd.blpop(0, EMPTY_QUEUE);
            } catch (Exception ex) {
                System.out.println("[poisoner] 退出: " + ex.getClass().getSimpleName());
            }
        }, "biz-blpop-dedicated");
        poisoner.setDaemon(true);
        poisoner.start();
        Thread.sleep(200);

        // 业务 INCR 线程跑在 sharedConn 上 —— 跟 BLPOP 完全没关系
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            new Thread(() -> {
                try {
                    ready.countDown();
                    fire.await();
                    sharedCmd.incr(COUNTER_KEY);
                    okCnt.incrementAndGet();
                } catch (RedisCommandTimeoutException ex) {
                    timeoutCnt.incrementAndGet();
                } catch (Exception ignored) {
                    // ignore for this lab
                } finally {
                    done.countDown();
                }
            }, "biz-incr-shared-" + i).start();
        }

        ready.await();
        long t0 = System.nanoTime();
        fire.countDown();
        done.await();
        long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        System.out.println("═══════════ L3-03 实验③ 专用连接隔离 BLPOP，普通命令零影响 ═══════════");
        System.out.printf("INCR 线程数      = %d%n", THREADS);
        System.out.printf("成功            = %d%n", okCnt.get());
        System.out.printf("超时            = %d%n", timeoutCnt.get());
        System.out.printf("Redis 计数器值  = %s%n", sharedCmd.get(COUNTER_KEY));
        System.out.printf("总耗时          = %d ms%n", costMs);
        System.out.printf("sharedConn      = %s%n", System.identityHashCode(sharedConn));
        System.out.printf("dedicatedConn   = %s%n", System.identityHashCode(dedicatedConn));
        System.out.println("══════════════════════════════════════════════════════════════════════");
        System.out.println("💡 结论：阻塞命令必须有自己的专用连接 —— 这就是 LettuceConnection 内部为");
        System.out.println("    MULTI/SUBSCRIBE/BLPOP 等切换 asyncDedicatedConnection 的真正动机。");

        // 不 close dedicatedConn（poisoner 还在 BLPOP），交给 shutdown 强制断
        client.shutdown(Duration.ZERO, Duration.ZERO);
        System.exit(0);
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
