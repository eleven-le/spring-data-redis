package org.springframework.data.redis.laboratory.l3_08.creation;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.laboratory.l3_08.support.L308RedisConfig;

/**
 * <h3>L3-08 / 🐣 创建阶段 · 实验 1:连接对象到底什么时候被造出来?</h3>
 *
 * <p><b>设计追问</b>:你以为 Bean 容器一启动 Redis 连接就建好了?其实分两段:</p>
 * <ol>
 *   <li><b>容器启动期</b>(早):{@link LettuceConnectionFactory#afterPropertiesSet()}
 *       — 只构建 {@code RedisClient}(Lettuce 客户端外壳)和异常转换器,
 *       <b>还没有打开任何 TCP Channel</b>。这是 Spring InitializingBean 接口的钩子。</li>
 *   <li><b>首次使用期</b>(晚):{@link LettuceConnectionFactory#getConnection()}
 *       内部调用 {@link LettuceConnectionFactory#getSharedConnection()},
 *       <b>第一次访问</b>才真正 lazy 打开物理 TCP 连接(Netty Channel)。</li>
 * </ol>
 *
 * <h4>断点指引</h4>
 * <ul>
 *   <li>STEP-1 → {@code LettuceConnectionFactory#afterPropertiesSet}(约 359 行)</li>
 *   <li>STEP-2 → {@code LettuceConnectionFactory#getConnection}(约 480 行)
 *       — 观察其先 {@code assertInitialized()} 再 {@code getSharedConnection()}</li>
 *   <li>STEP-3 → {@code LettuceConnectionFactory#getSharedConnection}
 *       — 看到 {@code if (connection == null) initConnection()} 的 lazy 模式</li>
 * </ul>
 *
 * <h4>「为什么这么设计」三连问</h4>
 * <ul>
 *   <li><b>Why lazy?</b>容器启动阶段 Redis 可能未就绪,eager 会启动失败;
 *       lazy 让容器先起来,故障留给运行时的重连机制。</li>
 *   <li><b>What if eager?</b>批处理场景容器秒级启动 → Redis 网络抖动 → 全量重启失败,
 *       SaaS 多租户场景灾难放大。</li>
 *   <li><b>Alternative?</b>可在 {@code afterPropertiesSet} 后调用 {@code initConnection()}
 *       手动预热(本 Demo 第二段演示)。</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class LettuceConnectionCreationDemo {

    public static void main(String[] args) {

        System.out.println("🚦 STEP-0: 容器即将启动,Spring 会扫描 @Configuration 并触发 Bean 生命周期");

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(L308RedisConfig.class)) {

            System.out.println("🚦 STEP-1: 容器已启动 → afterPropertiesSet 已跑完");
            System.out.println("           此刻 RedisClient 已存在,但还没有任何 TCP 连接打开");

            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);

            System.out.println("🚦 STEP-2: 即将首次 getConnection() → 这一行才真正打开物理连接");
            RedisConnection conn = factory.getConnection();
            System.out.println("           已拿到 RedisConnection: " + conn.getClass().getSimpleName());

            System.out.println("🚦 STEP-3: 第二次 getConnection() → 不会重新建 TCP,共享同一条 Channel");
            RedisConnection conn2 = factory.getConnection();
            System.out.println("           第二次拿到的 nativeConnection 与第一次同源(共享多路复用)");

            conn.close();
            conn2.close();

            System.out.println("🚦 STEP-4: 容器关闭 → destroy() 会关闭 RedisClient + Netty EventLoopGroup");
        }

        System.out.println("✅ Demo 完毕");
    }
}
