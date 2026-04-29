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
 * L3-03 实验②：BLPOP 在共享连接上"卡死整条马路"
 * <p>
 * 【实验意图】
 * 把 INCR 换成 BLPOP 0 nonexistent:queue，让你亲眼看到：
 * 一条永远不返回的阻塞命令，会怎样把同一条共享连接上后续的 INCR 全部卡死。
 * <p>
 * 【这不是 Redis 挂了，也不是线程池坏了】
 * 这是因为：
 *   - Redis 协议是请求-响应，单连接命令必须按顺序回包
 *   - BLPOP 0 占住的是 "Channel 上等待响应的窗口"
 *   - 后续 INCR 即使写进 Channel，也得排在 BLPOP 之后等响应
 *   - Redis 服务端不返回 BLPOP 的结果，整条连接的响应窗口就一直锁在 BLPOP 上
 * <p>
 * 【运行前提】
 *   docker run -d -p 6379:6379 redis:7
 *   提前清理：DEL lab:l3_03:nonexistent:queue
 * <p>
 * 【预期现象】
 *   - 阻塞线程：永远停在 cmd.blpop(0, ...) 不返回
 *   - 业务 INCR 线程：在 commandTimeout=2s 后批量抛 RedisCommandTimeoutException
 *   - 输出末尾 timeoutCnt ≈ THREADS，okCnt ≈ 0
 *   - 即使你把 commandTimeout 调到 10s，结果是一样的：连接被毒化
 *
 * @author leiyuhang
 * @since 2026-04-26
 */
public class BlockingCommandPoisonSharedConnectionLab {

    private static final String COUNTER_KEY = "lab:l3_03:poison:counter";
    private static final String EMPTY_QUEUE = "lab:l3_03:nonexistent:queue";
    private static final int THREADS = 50;

    public static void main(String[] args) throws Exception {

        RedisURI uri = buildRedisURI();
        RedisClient client = RedisClient.create(uri);

        // 共享连接：所有线程（包括 BLPOP 那个）都从同一条 Channel 上发命令
        StatefulRedisConnection<String, String> shared = client.connect();
        // 关键：把 sync 命令的总超时设短一点，否则你要等到地老天荒才看到现象
        shared.setTimeout(Duration.ofSeconds(2));
        RedisCommands<String, String> cmd = shared.sync();
        cmd.del(EMPTY_QUEUE);
        cmd.set(COUNTER_KEY, "0");

        AtomicInteger okCnt = new AtomicInteger();
        AtomicInteger timeoutCnt = new AtomicInteger();
        AtomicInteger otherErrCnt = new AtomicInteger();

        // ──────── 第①步：一个"投毒线程"先抢占 Channel，发出 BLPOP 0 ────────
        Thread poisoner = new Thread(() -> {
            try {
                System.out.println("[poisoner] 开始 BLPOP 0 一个永不存在的队列，准备霸占共享连接...");
                cmd.blpop(0, EMPTY_QUEUE);
                System.out.println("[poisoner] 不可能走到这里，除非有人 LPUSH 了 " + EMPTY_QUEUE);
            } catch (Exception ex) {
                System.out.println("[poisoner] 异常退出: " + ex.getClass().getSimpleName());
            }
        }, "biz-poisoner");
        poisoner.setDaemon(true);
        poisoner.start();

        // 给 poisoner 一点时间真的把 BLPOP 写到 Channel 上
        Thread.sleep(200);

        // ──────── 第②步：一群"无辜的"业务线程在同一条共享连接上调 INCR ────────
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            new Thread(() -> {
                try {
                    ready.countDown();
                    fire.await();
                    cmd.incr(COUNTER_KEY);
                    okCnt.incrementAndGet();
                } catch (RedisCommandTimeoutException timeout) {
                    timeoutCnt.incrementAndGet();
                } catch (Exception other) {
                    otherErrCnt.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }, "biz-incr-" + i).start();
        }

        ready.await();
        long t0 = System.nanoTime();
        fire.countDown();
        done.await();
        long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        System.out.println("═══════════════════ L3-03 实验② 共享连接被 BLPOP 毒化 ═══════════════════");
        System.out.printf("INCR 总线程数 = %d%n", THREADS);
        System.out.printf("成功         = %d%n", okCnt.get());
        System.out.printf("超时         = %d  ← 被前面的 BLPOP 卡住，全员超时%n", timeoutCnt.get());
        System.out.printf("其他异常     = %d%n", otherErrCnt.get());
        System.out.printf("耗时         = %d ms（≈ commandTimeout）%n", costMs);
        System.out.println("════════════════════════════════════════════════════════════════════════");
        System.out.println("💡 结论：共享连接前面排了一辆永不通过的 ETC 车，后面的车全部超时。");
        System.out.println("⚠️  这就是为什么 Lettuce 默认对 BLPOP/MULTI/SUBSCRIBE 等会启用 dedicated connection。");

        // 注意：不主动 close shared，因为 poisoner 还在 BLPOP，强行 close 会触发其它异常
        // 真实生产里：把 BLPOP 放专用连接、把它的 lifecycle 单独管。
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
