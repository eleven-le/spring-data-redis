package org.springframework.data.redis.laboratory.l3_08.toushi.object_pool_generic;

/**
 * <h3>🥷 偷师 demo:ObjectPool 借/还/耗尽全流程</h3>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class MiniObjectPoolDemo {

    public static void main(String[] args) {

        MiniObjectPool<String> pool = new MiniObjectPool<String>(new MiniObjectPool.Factory<String>() {
            int seq = 0;
            @Override public String create() { return "conn-" + (++seq); }
            @Override public boolean validate(String obj) { return true; }
            @Override public void destroy(String obj) { System.out.println("  [Factory] destroy " + obj); }
        }, 2, true);

        System.out.println("🚦 STEP-1: 借 2 条,池满");
        String a = pool.borrow();
        String b = pool.borrow();

        System.out.println("🚦 STEP-2: 第三次借 → 耗尽抛异常");
        try {
            pool.borrow();
        } catch (IllegalStateException e) {
            System.out.println("           " + e.getMessage());
        }

        System.out.println("🚦 STEP-3: 归还 1 条,再借就能拿到 idle 的");
        pool.returnObj(a);
        String c = pool.borrow();
        System.out.println("           c = " + c + " (复用了 a)");
    }
}
