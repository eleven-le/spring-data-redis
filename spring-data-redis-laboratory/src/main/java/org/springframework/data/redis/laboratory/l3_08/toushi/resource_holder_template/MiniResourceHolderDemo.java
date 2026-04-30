package org.springframework.data.redis.laboratory.l3_08.toushi.resource_holder_template;

/**
 * <h3>🥷 偷师 demo:嵌套调用下的引用计数行为</h3>
 *
 * <p>模拟 outer-method → inner-method 嵌套都借同一个资源,内层 release 不能真关,
 * 外层 release 才真关。这与 Spring 事务嵌套场景下 RedisConnection 的行为完全一致。</p>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class MiniResourceHolderDemo {

    public static void main(String[] args) {

        System.out.println("🚦 outer 借资源");
        String r1 = MiniResourceHolderTemplate.get("payment-session");

        System.out.println("🚦 inner 借同一资源(嵌套调用)");
        String r2 = MiniResourceHolderTemplate.get("payment-session");

        System.out.println("           outer == inner ? " + r1.equals(r2));

        System.out.println("🚦 inner 释放(ref 1→0?不,1)");
        MiniResourceHolderTemplate.release("payment-session");

        System.out.println("🚦 outer 释放(ref 0,真关)");
        MiniResourceHolderTemplate.release("payment-session");
    }
}
