package org.springframework.data.redis.laboratory.l3_08.validation;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.laboratory.l3_08.support.L308RedisConfig;

/**
 * <h3>L3-08 / ✅ 验证阶段 · 实验 2:validateConnection 在哪里手动触发?</h3>
 *
 * <p>SDR 在 {@link LettuceConnectionFactory} 上提供了一个对外的
 * {@code validateConnection()} 公有方法(源码 ~678 行):</p>
 *
 * <pre><code>
 * public void validateConnection() {
 *     getOrCreateSharedConnection().validateConnection();
 *     getOrCreateSharedReactiveConnection().validateConnection();
 * }
 * </code></pre>
 *
 * <p>这条路径与「池化 testOnBorrow」是两条独立的验证通道:</p>
 * <ul>
 *   <li><b>共享连接路径</b>:由 {@code SharedConnection#validateConnection} 走。
 *       默认<b>不会自动跑</b>,需要业务/运维侧手动触发(比如健康检查端点)。</li>
 *   <li><b>池化路径</b>:由 {@code GenericObjectPool#testOnBorrow} 触发,
 *       归 Apache Commons Pool 2 + Lettuce 的 {@code ConnectionPoolSupport} 实现。</li>
 * </ul>
 *
 * <h4>断点指引</h4>
 * <ul>
 *   <li>{@code LettuceConnectionFactory#validateConnection}(~678 行)</li>
 *   <li>{@code SharedConnection#validateConnection} — 看它发的是 PING(Lettuce 内部 RedisCommands.ping)</li>
 *   <li>{@code LettuceConnectionFactory#setValidateConnection} — 这个 setter <b>实际只在
 *       getConnection 重连时才会被读到</b>,日常并不会每次自动 PING</li>
 * </ul>
 *
 * <h4>古茗实战:健康检查端点</h4>
 * <p>把 {@code factory.validateConnection()} 包成一个 Spring Boot Actuator HealthIndicator,
 * 比 {@code RedisHealthIndicator} 默认实现更轻(默认实现是发 INFO 命令解析全部 metrics)。</p>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class ValidationCommandPingDemo {

    public static void main(String[] args) {

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(L308RedisConfig.class)) {

            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);

            System.out.println("🚦 STEP-1: 触发一次 validateConnection() → 内部走 PING");
            long start = System.nanoTime();
            factory.validateConnection();
            System.out.println("           耗时 " + (System.nanoTime() - start) / 1000 + " μs");

            System.out.println("🚦 STEP-2: 连发 100 次,验证是同步阻塞 RTT");
            start = System.nanoTime();
            for (int i = 0; i < 100; i++) factory.validateConnection();
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            System.out.println("           100 次累计 " + elapsed + " ms,平均每次 ~" + (elapsed / 100.0) + " ms");

            System.out.println("\n💡 由此可见:validateConnection 的代价就是一次 RTT。");
            System.out.println("   不要在热路径调用,但在熔断恢复 / 健康检查 / 故障转移触发后非常有用。");
        }
    }
}
