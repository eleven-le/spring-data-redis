package org.springframework.data.redis.laboratory.l3_08.usage;

import java.util.concurrent.CountDownLatch;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l3_08.support.L308RedisConfig;

/**
 * <h3>L3-08 / 🛠️ 使用阶段 · 实验 1:shareNativeConnection 多路复用</h3>
 *
 * <p>{@link LettuceConnectionFactory#getConnection()} 默认返回的 {@code LettuceConnection}
 * 内部包裹的是 <b>同一条共享 Channel</b>(由 {@code SharedConnection} 持有)。
 * 8 个线程并发 SET,Lettuce 在 Netty pipeline 上排队,顺序写入,
 * <b>没有任何锁竞争</b>(这就是 Redis 单线程模型 + Netty NIO 多路复用的天作之合)。</p>
 *
 * <h4>Jedis 对照</h4>
 * <p>Jedis 是阻塞 IO,一个连接同一时刻只能被一个线程使用,
 * 8 线程并发 → 必须建 8 条物理连接(或在池里排队)。
 * 这就是为什么 Spring Boot 2.7 默认推荐 Lettuce 的根本原因。</p>
 *
 * <h4>断点指引</h4>
 * <ul>
 *   <li>{@code RedisTemplate#execute(RedisCallback)} → finally 块走 release</li>
 *   <li>{@code RedisConnectionUtils#doGetConnection} — 看 ThreadLocal 没绑定时直接 factory.getConnection</li>
 *   <li>{@code LettuceConnection#openPipeline / nativeConnection.async()} — 真正发命令的 API 边界</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class ShareNativeConnectionDemo {

    public static void main(String[] args) throws Exception {

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(L308RedisConfig.class)) {

            StringRedisTemplate template = context.getBean(StringRedisTemplate.class);

            int threads = 8;
            int perThread = 1000;
            CountDownLatch done = new CountDownLatch(threads);

            System.out.println("🚦 STEP-1: " + threads + " 线程 × " + perThread + " 次 SET,共享一条 nativeConnection");
            long start = System.nanoTime();

            for (int i = 0; i < threads; i++) {
                final int tid = i;
                new Thread(() -> {
                    try {
                        for (int j = 0; j < perThread; j++) {
                            template.opsForValue().set("lab:l3_08:share:" + tid + ":" + j, "v");
                        }
                    } finally {
                        done.countDown();
                    }
                }, "writer-" + i).start();
            }
            done.await();

            long elapsed = (System.nanoTime() - start) / 1_000_000;
            int total = threads * perThread;
            System.out.println("🚦 STEP-2: 总耗时 " + elapsed + " ms,QPS ≈ " + (total * 1000L / elapsed));
            System.out.println("           没有创建第 2 条物理连接 → 这就是 Lettuce 的多路复用红利");
        }
    }
}
