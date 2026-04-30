package org.springframework.data.redis.laboratory.l3_08.validation;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l3_08.support.L308PooledRedisConfig;

/**
 * <h3>L3-08 / ✅ 验证阶段 · 实验 3:Evictor 后台守护线程现场</h3>
 *
 * <p>{@code BaseGenericObjectPool$Evictor} 是 Apache Commons Pool 2 的"清道夫":</p>
 * <ul>
 *   <li>由 {@code GenericObjectPool#startEvictor} 启动一个 {@code ScheduledThreadPoolExecutor},
 *       默认线程名 {@code commons-pool-evictor-thread}(JDK 9+ 用 {@code commons-pool-evictor})。</li>
 *   <li>调度周期 = {@code timeBetweenEvictionRuns}(本 Demo 配的是 3 秒)。</li>
 *   <li>每轮 {@link java.util.concurrent.ScheduledExecutorService#scheduleAtFixedRate} 触发
 *       {@code evict()} 方法,做 3 件事:
 *     <ol>
 *       <li>对空闲对象逐个 {@code testWhileIdle} 验证(发 PING)</li>
 *       <li>把空闲超过 {@code minEvictableIdleTime} 的对象 {@code destroyObject} 销毁</li>
 *       <li>保证池中 {@code minIdle} 不低于阈值,不够就 {@code makeObject} 补充</li>
 *     </ol>
 *   </li>
 * </ul>
 *
 * <h4>断点指引</h4>
 * <ul>
 *   <li>{@code BaseGenericObjectPool$Evictor#run} — 调度入口</li>
 *   <li>{@code GenericObjectPool#evict} — 实际清理逻辑(注意 EvictionPolicy SPI)</li>
 *   <li>{@code DefaultEvictionPolicy#evict} — 内置策略:idleTime > minEvictableIdleTime → 驱逐</li>
 * </ul>
 *
 * <h4>古茗经验</h4>
 * <p>商详业务高低峰差 10x,白天 maxTotal 经常顶到 30,夜里只用 2。
 * 调小 {@code timeBetweenEvictionRuns} 到 5 秒、{@code minEvictableIdleTime} 到 60 秒,
 * 让 Evictor 快速回收夜间空闲连接,避免占着 Redis Server FD。</p>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class EvictorThreadObserveDemo {

    public static void main(String[] args) throws Exception {

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(L308PooledRedisConfig.class)) {

            StringRedisTemplate template = context.getBean("pooledStringRedisTemplate", StringRedisTemplate.class);

            System.out.println("🚦 STEP-1: 触发 1 次 SET,激活池化路径(borrow → return)");
            template.opsForValue().set("lab:l3_08:evictor", "v");

            System.out.println("🚦 STEP-2: 接下来 10 秒不再发任何命令,观察 JVM 中是否有 commons-pool-evictor 线程在跑");
            System.out.println("           (打开 jstack 或 IDEA Debug → Threads 面板,搜 'evict')");

            for (int i = 0; i < 10; i++) {
                Thread.sleep(1000);
                System.out.println("           [" + (i + 1) + "s] 当前活跃线程数 = " + Thread.activeCount());
            }

            System.out.println("\n💡 期望观察到:在 minEvictableIdleTime(本 Demo 5 秒)之后,");
            System.out.println("   池中那 1 条 idle 连接被 destroyObject 销毁,回到 numActive=0/numIdle=0。");
        }
    }
}
