package org.springframework.data.redis.laboratory.l3_04;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实验⑥：把 Lettuce 三个抽象（Factory / Provider / Pool）压到一个文件里，看清协作。
 *
 * <p>对应关系：
 * <pre>
 *   MiniLettuceConnectionFactory      ≈ LettuceConnectionFactory
 *   MiniLettuceConnectionProvider     ≈ LettuceConnectionProvider 接口
 *   MiniSharedConnectionProvider      ≈ LettuceConnectionFactory$SharedConnection（共享）
 *   MiniPoolingConnectionProvider     ≈ LettucePoolingConnectionProvider（池化）
 *   MockRedisConnection                ≈ StatefulRedisConnection
 *   GenericObjectPool                  ≈ Apache Commons Pool 2
 * </pre>
 *
 * <p>本实验同时演示：
 * - 普通命令走共享 Provider，1000 次命令也不会创建 1000 个连接
 * - 阻塞命令走 Pooling Provider，每条阻塞命令独占一个池化连接，互不毒化
 */
public class MiniLettuceConnectionPoolSimulation {

    // ---------- 1. 抽象接口 ----------
    interface MiniLettuceConnectionProvider {
        MockRedisConnection getConnection();
        void release(MockRedisConnection conn);
    }

    // ---------- 2. 共享 Provider（懒加载 + double-check） ----------
    static class MiniSharedConnectionProvider implements MiniLettuceConnectionProvider {
        private volatile MockRedisConnection shared;

        @Override
        public MockRedisConnection getConnection() {
            MockRedisConnection s = shared;
            if (s == null) {
                synchronized (this) {
                    if (shared == null) {
                        shared = new MockRedisConnection();
                        System.out.println("[shared] lazy create -> " + shared);
                    }
                    s = shared;
                }
            }
            return s;
        }

        @Override
        public void release(MockRedisConnection conn) {
            // 共享不归还、不关闭
        }
    }

    // ---------- 3. 池化 Provider（包一层 commons-pool2） ----------
    static class MiniPoolingConnectionProvider implements MiniLettuceConnectionProvider {
        private final GenericObjectPool<MockRedisConnection> pool;

        MiniPoolingConnectionProvider(int maxActive) {
            GenericObjectPoolConfig<MockRedisConnection> cfg = new GenericObjectPoolConfig<>();
            cfg.setMaxTotal(maxActive);
            cfg.setMaxIdle(maxActive);
            cfg.setMinIdle(0);
            cfg.setMaxWait(Duration.ofMillis(2000));
            cfg.setJmxEnabled(false);
            this.pool = new GenericObjectPool<>(new MockRedisConnectionFactory(), cfg);
        }

        @Override
        public MockRedisConnection getConnection() {
            try {
                return pool.borrowObject();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void release(MockRedisConnection conn) {
            pool.returnObject(conn);
        }

        void close() {
            pool.close();
        }
    }

    // ---------- 4. Factory：根据"命令类型"挑 Provider ----------
    static class MiniLettuceConnectionFactory {
        private final MiniSharedConnectionProvider sharedProvider = new MiniSharedConnectionProvider();
        private final MiniPoolingConnectionProvider poolingProvider = new MiniPoolingConnectionProvider(3);

        MiniLettuceConnectionProvider providerFor(boolean blockingCommand) {
            return blockingCommand ? poolingProvider : sharedProvider;
        }

        void shutdown() {
            poolingProvider.close();
        }
    }

    // ---------- 5. 跑给学员看 ----------
    public static void main(String[] args) throws Exception {
        MiniLettuceConnectionFactory factory = new MiniLettuceConnectionFactory();

        System.out.println("============ 场景①：普通命令走共享 ============");
        runShortCommands(factory, 1000);

        System.out.println();
        System.out.println("============ 场景②：阻塞命令走池化 ============");
        runBlockingCommands(factory, 5);

        factory.shutdown();
    }

    private static void runShortCommands(MiniLettuceConnectionFactory factory, int n) throws InterruptedException {
        MiniLettuceConnectionProvider provider = factory.providerFor(false);
        AtomicInteger ok = new AtomicInteger();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    MockRedisConnection conn = provider.getConnection();
                    try {
                        conn.exec("INCR sku:001");
                        ok.incrementAndGet();
                    } finally {
                        provider.release(conn);
                    }
                } catch (Exception ignore) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();
        System.out.println("✅ " + n + " 个普通命令完成，ok=" + ok.get() + "（注意：上面只有 1 行 'lazy create'）");
    }

    private static void runBlockingCommands(MiniLettuceConnectionFactory factory, int n) throws InterruptedException {
        MiniLettuceConnectionProvider provider = factory.providerFor(true);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final String tag = "BLPOP-" + i;
            new Thread(() -> {
                MockRedisConnection conn = null;
                try {
                    conn = provider.getConnection();
                    System.out.println("[" + tag + "] got " + conn + " (这是一条独立连接，不会污染共享通道)");
                    Thread.sleep(800);                  // 模拟阻塞
                } catch (Exception e) {
                    System.out.println("[" + tag + "] timeout: " + e);
                } finally {
                    if (conn != null) provider.release(conn);
                    done.countDown();
                }
            }).start();
        }
        done.await();
        System.out.println("✅ 5 条 BLPOP 各自独占连接，互不影响（max-active=3 时会有 2 条排队等池）");
    }
}
