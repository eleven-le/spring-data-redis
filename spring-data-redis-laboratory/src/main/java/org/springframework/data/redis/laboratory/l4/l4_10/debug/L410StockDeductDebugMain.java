package org.springframework.data.redis.laboratory.l4.l4_10.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.laboratory.l4.l4_10.compare.L410LuaVsJavaGetSetCompare;
import org.springframework.data.redis.laboratory.l4.l4_10.config.L410RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_10.result.StockDeductResult;
import org.springframework.data.redis.laboratory.l4.l4_10.scenario.L410StockDeductLuaScenario;

import java.util.UUID;

/**
 * 生产级·秒杀库存扣减 + 超卖对比 + 幂等重放 + 用户限购演示。
 * <p>
 * 输出包括:
 * <ul>
 *   <li>每次扣减的 txId / remain / userPurchased / userLimitLeft</li>
 *   <li>同一 requestId 重放命中,idempotent=true 且 txId 与上次一致</li>
 *   <li>触发用户限购时的 code=-2 + userLimitLeft=0</li>
 *   <li>500 次并发扣减下 Lua 守恒(remain=0)+ JAVA 路径超卖(remain<0)</li>
 *   <li>流水尾部 5 条 JSON,可直接和 redis-cli LRANGE 对照</li>
 * </ul>
 */
public class L410StockDeductDebugMain {

    public static void main(String[] args) throws InterruptedException {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(L410RedisConfig.class)) {

            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);
            ObjectMapper mapper = ctx.getBean(ObjectMapper.class);
            @SuppressWarnings("unchecked")
            RedisScript<String> stockDeductScript = (RedisScript<String>) ctx.getBean("stockDeductScript");

            L410StockDeductLuaScenario scenario = new L410StockDeductLuaScenario(template, stockDeductScript, mapper);

            // ====== 单用户语义 ======
            String sku = "matcha-2026";
            scenario.clearAll(sku);
            scenario.initStock(sku, 5);
            long activityEnd = System.currentTimeMillis() + 600_000L;
            String userId = "u1001";
            long userMaxLimit = 2;

            System.out.println("=== 1) 单用户限购 2 杯,5 杯库存,7 次扣减观察 ===");
            for (int i = 0; i < 7; i++) {
                StockDeductResult r = scenario.deduct(sku, userId, 1, userMaxLimit, UUID.randomUUID().toString(), activityEnd);
                System.out.println("deduct#" + i + " -> " + r);
            }

            System.out.println("\n=== 2) 同一 requestId 幂等重放 ===");
            scenario.clearAll(sku);
            scenario.initStock(sku, 5);
            String reqId = UUID.randomUUID().toString();
            StockDeductResult first = scenario.deduct(sku, "u2002", 1, userMaxLimit, reqId, activityEnd);
            StockDeductResult replay = scenario.deduct(sku, "u2002", 1, userMaxLimit, reqId, activityEnd);
            System.out.println("first  -> " + first);
            System.out.println("replay -> " + replay + "  (期望 idempotent=true,txId 与 first 相同)");

            // ====== 并发对比 ======
            System.out.println("\n=== 3) 100×5=500 次并发扣减,初始库存 100,Lua vs Unsafe Java ===");
            L410LuaVsJavaGetSetCompare cmp = new L410LuaVsJavaGetSetCompare(template, stockDeductScript, mapper);
            long luaRemain = cmp.simulateConcurrentDeduct("sku-lua", 100, 100, 5, true);
            long javaRemain = cmp.simulateConcurrentDeduct("sku-java", 100, 100, 5, false);
            System.out.println("LUA  剩余 = " + luaRemain + " (期望 0,无超卖)");
            System.out.println("JAVA 剩余 = " + javaRemain + " (大概率 < 0,超卖)");

            // 清理
            scenario.clearAll(sku);
            scenario.clearAll("sku-lua");
            scenario.clearAll("sku-java");
        }
    }
}
