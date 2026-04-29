package org.springframework.data.redis.laboratory.l3_04;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模拟一条 "Redis 连接"。
 *
 * <p>没有真 TCP，只是为了让 GenericObjectPool 看到一个有创建/校验/销毁开销的对象。
 * 学员要看的是池行为，不是 Redis。
 */
public class MockRedisConnection {

    private static final AtomicInteger ID_GEN = new AtomicInteger();

    private final int id = ID_GEN.incrementAndGet();

    private volatile boolean alive = true;
    private final long createdAt = System.currentTimeMillis();

    public int getId() {
        return id;
    }

    public boolean isAlive() {
        return alive;
    }

    /**
     * 模拟"连接坏了"——例如服务端关连接、网络抖动、空闲驱逐窗口踩中。
     */
    public void breakIt() {
        this.alive = false;
    }

    /**
     * 模拟一次 Redis 命令。
     */
    public String exec(String cmd) {
        if (!alive) {
            throw new IllegalStateException("conn#" + id + " is dead");
        }
        return "OK(conn#" + id + " -> " + cmd + ")";
    }

    public void close() {
        alive = false;
    }

    public long ageMillis() {
        return System.currentTimeMillis() - createdAt;
    }

    @Override
    public String toString() {
        return "MockRedisConnection#" + id + (alive ? "" : "[BROKEN]");
    }
}
