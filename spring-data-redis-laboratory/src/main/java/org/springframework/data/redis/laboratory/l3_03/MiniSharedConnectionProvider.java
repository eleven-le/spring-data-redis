package org.springframework.data.redis.laboratory.l3_03;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.data.redis.laboratory.util.RedisConfigUtils;

import java.util.concurrent.atomic.AtomicReference;

/**
 * L3-03 实验④：极简 SharedConnectionProvider
 * <p>
 * 【实验意图】
 * 用 50 行代码模拟 LettuceConnectionProvider 拿共享连接的核心逻辑：
 *   - 第一次 getConnection() 时 lazy create
 *   - 后续永远返回同一个 StatefulRedisConnection 实例
 *   - 双重检查锁保证只创建一次
 * 这就是 Spring Data Redis 内部 SharedConnection.getConnection() 的"骨架"。
 * <p>
 * 【为什么不能 new ConcurrentHashMap.computeIfAbsent？】
 * Lettuce 真实代码里还需要：
 *   - 健康检查（连接是否还活着）
 *   - 连接重置（断线后重新建连）
 *   - 不同类型连接（普通 / pub-sub / sentinel / cluster）
 * 这里只演示"共享连接"的核心命脉：lazy + singleton。
 * <p>
 * 【运行前提】
 *   docker run -d -p 6379:6379 redis:7
 *
 * @author leiyuhang
 * @since 2026-04-26
 */
public class MiniSharedConnectionProvider {

    private final RedisClient client;
    private final AtomicReference<StatefulRedisConnection<String, String>> sharedRef = new AtomicReference<>();
    private final Object lock = new Object();

    public MiniSharedConnectionProvider(RedisClient client) {
        this.client = client;
    }

    /**
     * 拿共享连接：双重检查 + 懒加载，整个进程只 connect 一次。
     */
    public StatefulRedisConnection<String, String> getSharedConnection() {
        StatefulRedisConnection<String, String> conn = sharedRef.get();
        if (conn != null && conn.isOpen()) {
            return conn;
        }
        synchronized (lock) {
            conn = sharedRef.get();
            if (conn == null || !conn.isOpen()) {
                System.out.println("[provider] 触发 lazy connect... thread=" + Thread.currentThread().getName());
                conn = client.connect();
                sharedRef.set(conn);
            }
            return conn;
        }
    }

    /**
     * 拿专用连接：永远新建一条，调用方负责关闭。
     */
    public StatefulRedisConnection<String, String> getDedicatedConnection() {
        return client.connect();
    }

    public void shutdown() {
        StatefulRedisConnection<String, String> conn = sharedRef.getAndSet(null);
        if (conn != null) {
            conn.close();
        }
    }

    // ─────────────────────────── demo entry ───────────────────────────
    public static void main(String[] args) throws Exception {
        RedisURI redisURI = buildRedisURI();
        RedisClient client = RedisClient.create(redisURI);
        MiniSharedConnectionProvider provider = new MiniSharedConnectionProvider(client);

        // 10 个线程同时第一次拿共享连接
        Thread[] ts = new Thread[10];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> {
                StatefulRedisConnection<String, String> c = provider.getSharedConnection();
                System.out.printf("[t=%s] got shared @id=%s%n",
                        Thread.currentThread().getName(), System.identityHashCode(c));
            }, "lazy-init-" + i);
        }
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();

        System.out.println("─────────");
        System.out.println("💡 你应该只看到一行 [provider] 触发 lazy connect，所有线程拿到的 instance id 完全相同。");

        provider.shutdown();
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
