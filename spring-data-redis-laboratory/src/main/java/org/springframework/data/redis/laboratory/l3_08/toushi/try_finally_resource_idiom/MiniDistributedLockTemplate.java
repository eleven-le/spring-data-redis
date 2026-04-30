package org.springframework.data.redis.laboratory.l3_08.toushi.try_finally_resource_idiom;

import java.util.function.Supplier;

/**
 * <h3>🥷 偷师 demo:把 SDR 的 execute(callback) 范式搬到「分布式锁模板」</h3>
 *
 * <p>这就是为什么 RedissonClient 要做 {@code RLock.execute(action)} 这种 API —
 * 它本质上跟 {@code RedisTemplate#execute(RedisCallback)} 是同一个套路。</p>
 *
 * <h4>古茗落地</h4>
 * <pre>
 * String result = distributedLockTemplate.execute("sku:lock:" + skuId, () -&gt; {
 *     // 业务代码:扣库存 / 更价格
 *     return inventoryService.deduct(skuId, qty);
 * });
 * </pre>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class MiniDistributedLockTemplate {

    public <T> T execute(String key, Supplier<T> action) {
        LockHandle lock = acquire(key);
        try {
            System.out.println("  [LockTpl] enter critical section, key=" + key);
            return action.get();
        } finally {
            release(lock);
            System.out.println("  [LockTpl] released, key=" + key);
        }
    }

    private LockHandle acquire(String key) {
        System.out.println("  [LockTpl] acquire " + key + " ...");
        return new LockHandle(key, System.currentTimeMillis());
    }

    private void release(LockHandle lock) {
        System.out.println("  [LockTpl] release " + lock.key + " (held " +
                (System.currentTimeMillis() - lock.acquiredAt) + " ms)");
    }

    /** 普通类(非 record),兼容 Java 8 编译目标 */
    static class LockHandle {
        final String key;
        final long acquiredAt;
        LockHandle(String key, long acquiredAt) { this.key = key; this.acquiredAt = acquiredAt; }
    }

    public static void main(String[] args) {

        MiniDistributedLockTemplate tpl = new MiniDistributedLockTemplate();

        System.out.println("🚦 正常路径:扣库存");
        Integer remain = tpl.execute("sku:lock:123", new Supplier<Integer>() {
            @Override public Integer get() {
                System.out.println("  [Biz] 扣 1 件库存,剩余 99");
                return 99;
            }
        });
        System.out.println("           remain = " + remain);

        System.out.println("\n🚦 异常路径:业务抛异常,锁仍然被释放");
        try {
            tpl.execute("sku:lock:456", new Supplier<Integer>() {
                @Override public Integer get() { throw new RuntimeException("库存不足"); }
            });
        } catch (Exception e) {
            System.out.println("           捕获: " + e.getMessage());
            System.out.println("           ↑ 锁已经在 finally 里释放,完美");
        }
    }
}
