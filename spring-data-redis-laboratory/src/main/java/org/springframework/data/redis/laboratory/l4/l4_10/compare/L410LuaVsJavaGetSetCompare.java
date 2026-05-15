package org.springframework.data.redis.laboratory.l4.l4_10.compare;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.laboratory.l4.l4_10.L410Keys;
import org.springframework.data.redis.laboratory.l4.l4_10.result.StockDeductResult;
import org.springframework.data.redis.laboratory.l4.l4_10.scenario.L410StockDeductLuaScenario;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对比 1:Java 侧 GET+判断+DECRBY vs Lua 原子扣减。
 * <p>
 * 多线程并发跑同一份库存,直接看到不安全做法超卖、Lua 守恒。
 * Lua 路径走的是生产级 Scenario(返回结构化 JSON),并发后还能 tail 5 条流水验证 txId 单调递增。
 */
public class L410LuaVsJavaGetSetCompare {

    private final StringRedisTemplate template;
    private final L410StockDeductLuaScenario stockScenario;

    public L410LuaVsJavaGetSetCompare(StringRedisTemplate template,
                                      RedisScript<String> stockDeductScript,
                                      ObjectMapper mapper) {
        this.template = template;
        this.stockScenario = new L410StockDeductLuaScenario(template, stockDeductScript, mapper);
    }

    /**
     * 不安全:超卖反例。
     */
    public void unsafeJavaGetThenDecr(String skuId) {
        String key = L410Keys.stock(skuId);
        String raw = template.opsForValue().get(key);
        if (raw == null) return;
        long stock = Long.parseLong(raw);
        if (stock <= 0) return;
        // ===== 没有任何同步保护,多线程并发会撕开 =====
        template.opsForValue().decrement(key);
    }

    /**
     * 安全:Lua 生产级,返回带流水号 JSON。
     */
    public StockDeductResult safeLuaDeduct(String skuId, String userId, long activityEndMs) {
        return stockScenario.deduct(
                skuId, userId, 1, 999_999L,
                UUID.randomUUID().toString(), activityEndMs);
    }

    /**
     * 100 × 5 = 500 次扣减,初始库存 100。
     * Lua 路径期望:成功 100 次 + 失败 400 次 + 剩余 0 + 流水恰好 100 条。
     * Unsafe Java 路径期望:剩余可能 < 0(超卖),无法回滚。
     */
    public long simulateConcurrentDeduct(String skuId, long initStock, int threadCount, int perThread, boolean useLua) throws InterruptedException {
        String key = L410Keys.stock(skuId);
        stockScenario.clearAll(skuId);
        template.opsForValue().set(key, String.valueOf(initStock));

        long activityEnd = System.currentTimeMillis() + 600_000L;

        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();

        for (int t = 0; t < threadCount; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        if (useLua) {
                            StockDeductResult r = safeLuaDeduct(skuId, "u-" + tid + "-" + i, activityEnd);
                            if (r != null && r.isSuccess()) ok.incrementAndGet();
                            else fail.incrementAndGet();
                        } else {
                            unsafeJavaGetThenDecr(skuId);
                            ok.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }, "deduct-" + t).start();
        }

        latch.await();
        long remain = Long.parseLong(template.opsForValue().get(key));
        System.out.printf("[%s] threads=%d perThread=%d -> ok=%d fail=%d remain=%d%n",
                useLua ? "LUA" : "UNSAFE-JAVA", threadCount, perThread, ok.get(), fail.get(), remain);
        if (useLua) {
            System.out.println("流水尾部 5 条:");
            stockScenario.tailTx(skuId, 5).forEach(t1 -> System.out.println("  " + t1));
        }
        return remain;
    }
}
