package org.springframework.data.redis.laboratory.l3_08.eviction;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.laboratory.l3_08.support.L308PooledRedisConfig;

/**
 * <h3>L3-08 / 💀 驱逐阶段 · 实验 2:容器关闭时 destroy() 的全链路清理</h3>
 *
 * <p>当 {@code AnnotationConfigApplicationContext.close()} 触发时,Spring 会对所有
 * 实现 {@link org.springframework.beans.factory.DisposableBean} 的 Bean 调用 {@code destroy()}。
 * {@link LettuceConnectionFactory#destroy()} 内部做了 4 件事:</p>
 *
 * <ol>
 *   <li>关闭 {@code SharedConnection} — 把共享 Channel 主动 close</li>
 *   <li>关闭 {@code SharedReactiveConnection}(响应式版本)</li>
 *   <li>关闭 {@code AbstractRedisClient} — 等价 {@code RedisClient.shutdown(quiet, timeout, unit)}</li>
 *   <li>关闭 {@code ClientResources} — 释放 Netty {@code EventLoopGroup}(JVM 才能干净退出)</li>
 * </ol>
 *
 * <h4>shutdown 优雅期 vs 静默期</h4>
 * <ul>
 *   <li>{@code shutdownQuietPeriod}:开始关之后,先静默 N 毫秒不接新任务,等正在跑的命令结束</li>
 *   <li>{@code shutdownTimeout}:整体最长等多久,超时强制 close</li>
 *   <li><b>古茗踩坑</b>:默认 {@code shutdownTimeout=2s},一些 SaaS Pod 优雅停机限时 5s,
 *       不调小这两个参数,Pod 优雅停机经常被 K8s SIGKILL 强杀</li>
 * </ul>
 *
 * <h4>断点指引</h4>
 * <ul>
 *   <li>{@code LettuceConnectionFactory#destroy}(~398 行)</li>
 *   <li>{@code LettucePoolingConnectionProvider#destroy}(~256 行)— 关池子 + 销毁所有借出对象</li>
 *   <li>{@code AbstractRedisClient#shutdown} — Lettuce 客户端关闭实现</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class DestroyOnShutdownDemo {

    public static void main(String[] args) {

        long ctxStart = System.currentTimeMillis();
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(L308PooledRedisConfig.class);
        System.out.println("🚦 STEP-1: 容器启动耗时 " + (System.currentTimeMillis() - ctxStart) + " ms");

        LettuceConnectionFactory factory = context.getBean(
                "pooledLettuceConnectionFactory", LettuceConnectionFactory.class);
        factory.getConnection().close();
        System.out.println("🚦 STEP-2: 触发了 1 次 getConnection,池中已有 1 条 idle 连接");

        System.out.println("🚦 STEP-3: 即将 context.close() → 触发 LettuceConnectionFactory#destroy()");
        long destroyStart = System.currentTimeMillis();
        context.close();
        System.out.println("🚦 STEP-4: 关闭耗时 " + (System.currentTimeMillis() - destroyStart) + " ms");
        System.out.println("           ↑ 主要由 Lettuce shutdownQuietPeriod + shutdownTimeout 决定");
        System.out.println("           本 Demo 配的是 50ms quietPeriod + 200ms timeout,所以非常快");
    }
}
