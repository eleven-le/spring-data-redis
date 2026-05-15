package org.springframework.data.redis.laboratory.l4.l4_10.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.laboratory.l4.l4_10.config.L410RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_10.result.DelayQueueClaimResult;
import org.springframework.data.redis.laboratory.l4.l4_10.scenario.L410DelayQueueClaimLuaScenario;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 生产级·延迟队列原子抢占演示(含 payload + attempts + visibility)。
 * <p>
 * 投 20 个订单,每个 payload 是一段订单 JSON;4 个消费者并发抢占。
 * 期望:每个 taskId 只被一个消费者抢到、消费者拿到任务的同时拿到 payload + attempts + 可见超时时间。
 */
public class L410DelayQueueDebugMain {

    public static void main(String[] args) throws InterruptedException {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(L410RedisConfig.class)) {

            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);
            ObjectMapper mapper = ctx.getBean(ObjectMapper.class);
            @SuppressWarnings("unchecked")
            RedisScript<String> script = (RedisScript<String>) ctx.getBean("delayQueueClaimScript");

            String biz = "order-timeout";
            L410DelayQueueClaimLuaScenario q =
                    new L410DelayQueueClaimLuaScenario(template, script, mapper, biz);
            q.clearQueue();

            // 投 20 个已到期任务,每个带订单 JSON payload
            long now = System.currentTimeMillis();
            for (int i = 0; i < 20; i++) {
                String payload = String.format(
                        "{\"orderId\":\"O-1000%02d\",\"userId\":\"u-%d\",\"amount\":%d}",
                        i, 9000 + i, 1888 + i);
                q.addTask("order-" + i, payload, now - 1000);
            }

            // 4 个消费者并发抢
            int consumerN = 4;
            CountDownLatch latch = new CountDownLatch(consumerN);
            AtomicInteger total = new AtomicInteger();
            for (int c = 0; c < consumerN; c++) {
                final int cid = c;
                new Thread(() -> {
                    try {
                        DelayQueueClaimResult res = q.claimDueTasks(8, 30_000L, 3);
                        total.addAndGet(res.claimedCount == null ? 0 : res.claimedCount.intValue());
                        System.out.println("consumer#" + cid + " claimedCount=" + res.claimedCount);
                        if (res.claimed != null) {
                            for (DelayQueueClaimResult.Task t : res.claimed) {
                                System.out.println("  └─ " + t);
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                }, "consumer-" + c).start();
            }
            latch.await();
            System.out.println("\ntotal claimed = " + total.get() + " (期望 20,无重复)");
            q.clearQueue();
        }
    }
}
