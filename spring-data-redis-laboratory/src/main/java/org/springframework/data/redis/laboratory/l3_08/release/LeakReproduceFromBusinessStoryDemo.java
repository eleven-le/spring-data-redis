package org.springframework.data.redis.laboratory.l3_08.release;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisConnectionUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l3_08.support.L308PooledRedisConfig;

/**
 * <h3>L3-08 / 🔚 释放阶段 · 实验 2:复现业务故事 — RedisCallback 中途异常导致的连接泄漏</h3>
 *
 * <p><b>故事还原</b>:某服务自定义了 RedisCallback,内部 throw 异常前已经手动
 * 调过 {@code RedisConnectionUtils.getConnection(factory)} 借了第二条连接做副作用,
 * 但没有写 finally 释放它。线上表现:Redis 连接数缓慢爬升,凌晨 4 点池耗尽,
 * 全门店服务雪崩。</p>
 *
 * <h4>本 Demo 的复现策略</h4>
 * <ul>
 *   <li>用 {@link L308PooledRedisConfig}:{@code maxTotal=2},便于秒级复现耗尽。</li>
 *   <li>跑 5 次"危险 callback":每次手动 borrow 一条 dedicated 连接但不释放。</li>
 *   <li>预期:第 3 次开始等待 {@code maxWait=2s},第 4 次开始抛
 *       {@code PoolException: Could not get a resource from the pool}。</li>
 * </ul>
 *
 * <h4>断点指引</h4>
 * <ul>
 *   <li>{@code GenericObjectPool#borrowObject} — 看池中无可用对象时的等待逻辑</li>
 *   <li>{@code GenericObjectPool#assertOpen} → 抛 {@code NoSuchElementException}</li>
 *   <li>对照{@link CorrectFinallyReleaseDemo}的正确写法</li>
 * </ul>
 *
 * <h4>修复方法</h4>
 * <ol>
 *   <li>永远走 {@code template.execute(callback)},让 SDR 帮你写 finally release</li>
 *   <li>如必须手动 {@code getConnection},立刻配 try-finally + releaseConnection</li>
 *   <li>用 SkyWalking / Arthas 监控 {@code GenericObjectPool#getNumActive} 趋势,提前告警</li>
 * </ol>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class LeakReproduceFromBusinessStoryDemo {

    public static void main(String[] args) {

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(L308PooledRedisConfig.class)) {

            StringRedisTemplate template = context.getBean("pooledStringRedisTemplate", StringRedisTemplate.class);
            RedisConnectionFactory factory = context.getBean(
                    "pooledLettuceConnectionFactory", RedisConnectionFactory.class);

            for (int i = 1; i <= 5; i++) {
                try {
                    System.out.println("🚦 第 " + i + " 次执行危险 callback...");
                    template.execute((RedisCallback<Void>) connection -> {
                        // 业务命令:正常的
                        connection.ping();

                        // ⚠️ 危险代码:在 callback 内手动借第二条连接,准备做"副作用"
                        // 但接下来抛异常 → 这条 dedicated 连接永远不会被释放 → 泄漏!
                        RedisConnection leaked = RedisConnectionUtils.getConnection(factory);
                        leaked.ping();

                        // ❌ 故意不写 RedisConnectionUtils.releaseConnection(leaked, factory);
                        throw new RuntimeException("模拟业务异常");
                    });
                } catch (Exception e) {
                    System.out.println("           第 " + i + " 次异常: " + e.getClass().getSimpleName()
                            + " — " + e.getMessage());
                }
            }

            System.out.println("\n💀 复现完成。注意观察:");
            System.out.println("   1. 第 3~5 次会越来越慢,直到抛 PoolException(maxTotal=2 已耗尽)");
            System.out.println("   2. 改用 try-finally + releaseConnection(leaked, factory) 即可立刻修复");
        }
    }
}
