package org.springframework.data.redis.laboratory.l3_08.creation;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReferenceArray;

import io.lettuce.core.api.StatefulConnection;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.laboratory.l3_08.support.L308RedisConfig;

/**
 * <h3>L3-08 / 🐣 创建阶段 · 实验 3:shareNativeConnection 的 lazy 单例</h3>
 *
 * <p>{@code LettuceConnectionFactory#getSharedConnection()} 是「全局唯一原生 Channel」的入口,
 * 但它是 {@code protected}(不让业务直接拿,只允许子类继承重写)。
 * 本 Demo 用反射穿透,验证<b>多线程并发首次访问只会创建一份 nativeConnection</b>。</p>
 *
 * <h4>源码核心(LettuceConnectionFactory.java ~1215 行)</h4>
 * <pre><code>
 * protected StatefulRedisConnection&lt;byte[], byte[]&gt; getSharedConnection() {
 *     synchronized (this.connectionMonitor) {                  // 双检锁
 *         if (this.connection == null) {
 *             this.connection = createSharedConnection();      // 真正打开 Netty Channel
 *         }
 *         return this.connection;
 *     }
 * }
 * </code></pre>
 *
 * <h4>偷师点</h4>
 * <ul>
 *   <li><b>双检锁单例</b> — 高并发下 lazy init 教科书写法,可搬到自家 RPC/HTTP 长连接客户端。</li>
 *   <li><b>protected 而非 public</b> — 防止业务代码绕过 RedisTemplate 直接拿原生连接,
 *       但保留扩展能力(子类可加埋点/链路追踪)。这是 SDR 团队对「开闭原则」的优雅落地。</li>
 *   <li><b>shareNativeConnection 的隐含约束</b>:Channel 多路复用安全(Netty pipeline 排队),
 *       但 {@code MULTI/EXEC、SUBSCRIBE、BLPOP} 必须切到独占连接(L3-05 主线)。</li>
 * </ul>
 *
 * <h4>断点指引</h4>
 * <ul>
 *   <li>{@code LettuceConnectionFactory#getSharedConnection}     — 双检锁现场</li>
 *   <li>{@code LettuceConnectionFactory#createSharedConnection}  — 调 connectionProvider 真正 connect</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class SharedNativeConnectionLazyInitDemo {

    public static void main(String[] args) throws Exception {

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(L308RedisConfig.class)) {

            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);

            // 反射拿 protected getSharedConnection
            Method method = LettuceConnectionFactory.class.getDeclaredMethod("getSharedConnection");
            method.setAccessible(true);

            int threads = 8;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch fire = new CountDownLatch(1);
            AtomicReferenceArray<StatefulConnection<?, ?>> captured = new AtomicReferenceArray<>(threads);

            System.out.println("🚦 STEP-1: 8 个线程同步起跑,争抢 getSharedConnection");

            for (int i = 0; i < threads; i++) {
                final int idx = i;
                new Thread(() -> {
                    ready.countDown();
                    try { fire.await(); } catch (InterruptedException ignored) { return; }
                    try {
                        captured.set(idx, (StatefulConnection<?, ?>) method.invoke(factory));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, "borrow-" + i).start();
            }
            ready.await();
            fire.countDown();

            Thread.sleep(200); // 等线程跑完

            StatefulConnection<?, ?> first = captured.get(0);
            boolean allSame = true;
            for (int i = 1; i < threads; i++) {
                if (captured.get(i) != first) { allSame = false; break; }
            }

            System.out.println("🚦 STEP-2: 8 个线程拿到的 nativeConnection 是否同一对象?");
            System.out.println("           答案 = " + allSame + " (双检锁保证 lazy 单例)");
            System.out.println("           对象身份: " + System.identityHashCode(first));
        }
    }
}
