package org.springframework.data.redis.laboratory.l4.l4_08.scenario;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.L408Keys;
import org.springframework.data.redis.laboratory.l4.l4_08.transaction.L408Transactions;

import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * WATCH 冲突复现：用两个独立 RedisTemplate 模拟双客户端。
 * <p>
 * Spring Data Redis 默认在同一 RedisTemplate 上 SessionCallback 内部
 * 会让 watch/exec 落同一连接；要让另一"客户端"真正干扰它，最稳妥的做法是
 * 用 <b>另一个 LettuceConnectionFactory / 另一个 RedisTemplate</b> 来代表 Client B。
 * <p>
 * 这个类用于 {@code L408ConflictDebugMain} 跑通"WATCH 冲突 → EXEC 返回 null"的全过程。
 */
public class L408WatchConflictSimulationScenario {

    private final StringRedisTemplate clientATemplate;
    private final StringRedisTemplate clientBTemplate;

    public L408WatchConflictSimulationScenario(StringRedisTemplate clientATemplate,
                                                LettuceConnectionFactory clientBFactory) {
        this.clientATemplate = clientATemplate;
        // Client B 用独立 template 包独立 factory，确保物理上是两个连接
        this.clientBTemplate = new StringRedisTemplate(clientBFactory);
        this.clientBTemplate.afterPropertiesSet();
    }

    /**
     * Client A 的事务路径：WATCH → GET → 等待 latch → MULTI → SET → EXEC。
     * 在 latch 释放前，Client B 会修改同一个 key。
     */
    public List<Object> clientAWatchAndExec(String key, CountDownLatch readyLatch, CountDownLatch goLatch) {
        return L408Transactions.runTxString(clientATemplate, ops -> {
            ops.watch(key);
            ops.opsForValue().get(key);
            readyLatch.countDown(); // 通知 B 可以来改了
            try {
                goLatch.await();      // 等 B 改完
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            ops.multi();
            ops.opsForValue().set(key, "A-want-set");
            return ops.exec(); // 期望被取消
        });
    }

    /**
     * Client B 直接 SET，不走事务。
     */
    public void clientBModifyBeforeExec(String key, String newValue) {
        clientBTemplate.opsForValue().set(key, newValue);
    }

    /**
     * 串联两个 client 复现完整冲突。
     */
    public List<Object> simulateTwoClientsConflict(String key) {
        clientATemplate.opsForValue().set(key, "v0");
        CountDownLatch readyLatch = new CountDownLatch(1);
        CountDownLatch goLatch = new CountDownLatch(1);

        Thread bThread = new Thread(() -> {
            try {
                readyLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            clientBModifyBeforeExec(key, "B-modified");
            goLatch.countDown();
        }, "client-B");
        bThread.setDaemon(true);
        bThread.start();

        return clientAWatchAndExec(key, readyLatch, goLatch);
    }

    public void explainConflictResult() {
        System.out.println("=== WATCH 冲突结果说明 ===");
        System.out.println("Client A 在 WATCH 后、EXEC 前，watched key 被 Client B 修改；");
        System.out.println("Redis 检测到版本变化，EXEC 返回 nil；");
        System.out.println("Spring Data Redis 中 exec() 返回 null 或 emptyList；");
        System.out.println("业务侧应识别该信号并触发有限退避重试。");
    }

    public void clear(String key) {
        clientATemplate.delete(key);
    }

    public static String defaultKey() {
        return L408Keys.conflictDemo(1);
    }
}
