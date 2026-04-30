package org.springframework.data.redis.laboratory.l3_08.eviction;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l3_08.support.L308PooledRedisConfig;

/**
 * <h3>L3-08 / 💀 驱逐阶段 · 实验 1:EvictionPolicy 与默认策略</h3>
 *
 * <p>Apache Commons Pool 2 的 {@code EvictionPolicy} 是一个 SPI:</p>
 *
 * <pre><code>
 * public interface EvictionPolicy&lt;T&gt; {
 *     boolean evict(EvictionConfig config, PooledObject&lt;T&gt; underTest, int idleCount);
 * }
 * </code></pre>
 *
 * <p>默认实现 {@code DefaultEvictionPolicy}:</p>
 * <ul>
 *   <li>条件 1:{@code idleTime > softMinEvictableIdleTime AND idleCount > minIdle} → 驱逐</li>
 *   <li>条件 2:{@code idleTime > minEvictableIdleTime} → 驱逐(更激进)</li>
 *   <li>否则:留着</li>
 * </ul>
 *
 * <h4>偷师点</h4>
 * <p>Apache Pool 把"什么时候该回收"做成 SPI,你可以注入自家策略
 * (比如基于 RTT 抖动判定连接质量、基于业务低峰窗口加速回收)。这就是<b>策略模式 + SPI</b>的经典组合。</p>
 *
 * <h4>断点指引</h4>
 * <ul>
 *   <li>{@code GenericObjectPool#evict} — 主循环</li>
 *   <li>{@code DefaultEvictionPolicy#evict} — 默认策略</li>
 *   <li>{@code BaseGenericObjectPool#setEvictionPolicyClassName} — 注入自定义策略入口</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class EvictionPolicyDemo {

    public static void main(String[] args) throws Exception {

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(L308PooledRedisConfig.class)) {

            StringRedisTemplate template = context.getBean("pooledStringRedisTemplate", StringRedisTemplate.class);

            System.out.println("🚦 STEP-1: 触发 1 次 SET,池中产生 1 条 idle 连接");
            template.opsForValue().set("lab:l3_08:evict", "v");

            System.out.println("🚦 STEP-2: 等 8 秒(>minEvictableIdleTime=5s),让 Evictor 跑 2~3 轮");
            for (int i = 1; i <= 8; i++) {
                Thread.sleep(1000);
                System.out.println("           [" + i + "s]");
            }

            System.out.println("🚦 STEP-3: 再次 SET,看连接是不是被驱逐后重新创建的(可在 borrowObject 处打断点观察)");
            template.opsForValue().set("lab:l3_08:evict", "v2");

            System.out.println("\n💡 期望:STEP-3 时 numActive 重新 +1,但底层是新建的连接(老的已 destroyObject)。");
        }
    }
}
