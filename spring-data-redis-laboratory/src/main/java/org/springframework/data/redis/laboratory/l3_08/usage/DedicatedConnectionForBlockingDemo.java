package org.springframework.data.redis.laboratory.l3_08.usage;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l3_08.support.L308PooledRedisConfig;

/**
 * <h3>L3-08 / 🛠️ 使用阶段 · 实验 2:为什么 BLPOP 必须独占连接?</h3>
 *
 * <p><b>类比</b>:共享连接像电话客服线路,8 个客户的小问题(SET/GET)可以快速轮换。
 * 但是一旦一个客户开始<b>"挂断前必须听完语音留言"</b>(BLPOP 阻塞 N 秒),
 * 整条线路就被卡住了 — 其他业务的命令只能排队等。</p>
 *
 * <h4>SDR 内部机制</h4>
 * <p>{@code LettuceConnection#doGetAsyncDedicatedConnection} 在以下命令前会被触发:</p>
 * <ul>
 *   <li>BLPOP / BRPOP / BLMOVE — 阻塞列表命令</li>
 *   <li>XREAD BLOCK / XREADGROUP BLOCK — 阻塞流命令</li>
 *   <li>SUBSCRIBE / PSUBSCRIBE — Pub/Sub 必须独占</li>
 *   <li>MULTI / EXEC — 事务期间也独占(见 {@code DedicatedConnectionForTransactionDemo})</li>
 * </ul>
 *
 * <p>触发后,Lettuce 走 {@code LettucePoolingConnectionProvider#getConnection} 从池子借一条新连接,
 * 用完后 {@code release} 回池(归还前 {@code discardIfNecessary} 清理 MULTI 残留状态)。</p>
 *
 * <h4>断点指引</h4>
 * <ul>
 *   <li>{@code LettuceConnection#getAsyncDedicatedConnection}</li>
 *   <li>{@code LettucePoolingConnectionProvider#getConnection}(SDR ~94 行)</li>
 *   <li>{@code DefaultLettuceListCommands#bLPop}(SDR ListCommands 包)</li>
 * </ul>
 *
 * <h4>古茗实战陷阱(真实事故复盘)</h4>
 * <p>大促期间用 BLPOP 做异步任务消费,share-native 模式下 30 个消费者全部抢同一条共享连接,
 * 商详缓存读 RT 从 1ms 飙到 800ms。修法:消费者用独立 LettuceConnectionFactory(开池化)。</p>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class DedicatedConnectionForBlockingDemo {

    public static void main(String[] args) {

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(L308PooledRedisConfig.class)) {

            StringRedisTemplate template = context.getBean("pooledStringRedisTemplate", StringRedisTemplate.class);

            String queue = "lab:l3_08:blocking:queue";
            template.opsForList().rightPush(queue, "task-1");

            System.out.println("🚦 STEP-1: BLPOP 1 秒,会触发独占连接路径");
            long start = System.nanoTime();
            String popped = template.opsForList().leftPop(queue, java.time.Duration.ofSeconds(1));
            System.out.println("           BLPOP 拿到: " + popped + ",耗时 "
                    + (System.nanoTime() - start) / 1_000_000 + " ms");

            System.out.println("🚦 STEP-2: 第二次 BLPOP(队列空,等满 1 秒)");
            start = System.nanoTime();
            popped = template.opsForList().leftPop(queue, java.time.Duration.ofSeconds(1));
            System.out.println("           BLPOP 等待: " + popped + ",耗时 "
                    + (System.nanoTime() - start) / 1_000_000 + " ms");
            System.out.println("           ↑ 期间这条独占连接被「占用 1 秒」,池中可用连接 -1");
        }
    }
}
