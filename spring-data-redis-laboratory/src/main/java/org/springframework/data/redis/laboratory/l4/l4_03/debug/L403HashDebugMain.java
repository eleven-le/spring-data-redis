package org.springframework.data.redis.laboratory.l4.l4_03.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_03.config.L403RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_03.hash.L403HashBasicOperationsLab;
import org.springframework.data.redis.laboratory.l4.l4_03.hash.L403ShoppingCartHashScenario;
import org.springframework.data.redis.laboratory.l4.l4_03.hash.L403SkuStockHashScenario;
import org.springframework.data.redis.laboratory.l4.l4_03.hash.L403UserBehaviorHashScenario;
import org.springframework.data.redis.laboratory.l4.l4_03.hash.L403UserProfileHashScenario;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hash 系列 Debug 入口。
 * <p>
 * 推荐断点：
 * <ul>
 *   <li>{@code RedisTemplate#opsForHash}</li>
 *   <li>{@code DefaultHashOperations#put}</li>
 *   <li>{@code DefaultHashOperations#putAll}（看 HMSET 批量参数如何拼装）</li>
 *   <li>{@code RedisTemplate#execute}</li>
 *   <li>{@code LettuceConnection#hashCommands}</li>
 * </ul>
 */
public class L403HashDebugMain {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(L403RedisConfig.class)) {

            RedisTemplate<String, Object> template = (RedisTemplate<String, Object>) ctx.getBean("redisTemplate", RedisTemplate.class);

            System.out.println("════════ ① Hash 基础命令全家桶 ════════");
            new L403HashBasicOperationsLab(template).runAll();

            System.out.println("\n════════ ② 用户资料 ════════");
            L403UserProfileHashScenario profile = new L403UserProfileHashScenario(template);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("nickname", "奶茶狂魔");
            p.put("avatar", "https://cdn/avatar/123.png");
            p.put("gender", "F");
            p.put("level", 7);
            p.put("city", "上海");
            p.put("lastLoginTime", System.currentTimeMillis());
            profile.saveProfile("u-1001", p);
            profile.updateNickname("u-1001", "蜜雪冰城重度患者");
            System.out.println("整体读: " + profile.getProfile("u-1001"));
            System.out.println("局部读: " + profile.getPartialProfile("u-1001", Arrays.asList("nickname", "level", "city")));
            profile.deleteProfile("u-1001");

            System.out.println("\n════════ ③ 购物车 ════════");
            L403ShoppingCartHashScenario cart = new L403ShoppingCartHashScenario(template);
            cart.clearCart("u-1001");
            cart.clearCart("guest-99");
            cart.addSku("u-1001", "SKU-A", 1);
            cart.increaseSku("u-1001", "SKU-A", 2);   // 1 + 2 = 3
            cart.addSku("u-1001", "SKU-B", 5);
            cart.addSku("guest-99", "SKU-A", 4);
            cart.addSku("guest-99", "SKU-C", 2);
            cart.mergeGuestCartToUserCart("guest-99", "u-1001"); // SKU-A 合并 3+4=7
            System.out.println("合并后购物车: " + cart.getCart("u-1001"));
            cart.clearCart("u-1001");

            System.out.println("\n════════ ④ SKU 展示库存 ════════");
            L403SkuStockHashScenario stock = new L403SkuStockHashScenario(template);
            Map<String, Long> initial = new LinkedHashMap<>();
            initial.put("SKU-A", 100L);
            initial.put("SKU-B", 50L);
            stock.initSkuStock("item-x", initial);
            stock.decreaseSkuStockForDisplay("item-x", "SKU-A", 3);
            System.out.println("展示库存: " + stock.getAllSkuStock("item-x") + "  ⚠️ 这是展示数，不是真实库存");

            System.out.println("\n════════ ⑤ 用户行为 ════════");
            L403UserBehaviorHashScenario behavior = new L403UserBehaviorHashScenario(template);
            behavior.like("u-1001", "item-100");
            behavior.like("u-1001", "item-200");
            behavior.favorite("u-1001", "item-100");
            System.out.println("已点赞 item-100? " + behavior.hasLiked("u-1001", "item-100"));
            System.out.println("dump = " + behavior.debugDump("u-1001"));
            template.delete("l4:03:user:behavior:u-1001");

            System.out.println("\n✅ Hash 链路全跑通。Step Into ops.put 看源码。");
        }
    }
}
