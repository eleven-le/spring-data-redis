package org.springframework.data.redis.laboratory.l3_08.toushi.object_pool_generic;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * <h3>🥷 简化版 ObjectPool(80 行复刻 GenericObjectPool 核心)</h3>
 *
 * <p>用 {@code Deque<T> idle} 存空闲对象,{@code Set<T> active} 跟踪借出对象。
 * borrow 时优先复用 idle,空了就用 factory 创建。 testOnBorrow=true 时会验证再交付。</p>
 *
 * <h4>对照 Apache Pool</h4>
 * <ul>
 *   <li>{@code MiniObjectPool#borrow}      ↔ {@code GenericObjectPool#borrowObject}</li>
 *   <li>{@code MiniObjectPool#returnObj}   ↔ {@code GenericObjectPool#returnObject}</li>
 *   <li>{@code Factory<T>}                  ↔ {@code PooledObjectFactory}</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class MiniObjectPool<T> {

    /** 简化版的 PooledObjectFactory */
    public interface Factory<T> {
        T create();
        boolean validate(T obj);
        void destroy(T obj);
    }

    private final Factory<T> factory;
    private final int maxTotal;
    private final boolean testOnBorrow;
    private final Deque<T> idle = new ArrayDeque<>();
    private final Set<T> active = new HashSet<>();

    public MiniObjectPool(Factory<T> factory, int maxTotal, boolean testOnBorrow) {
        this.factory = factory;
        this.maxTotal = maxTotal;
        this.testOnBorrow = testOnBorrow;
    }

    public synchronized T borrow() {
        T obj;
        while (true) {
            if (!idle.isEmpty()) {
                obj = idle.poll();
            } else if (active.size() < maxTotal) {
                obj = factory.create();
                System.out.println("  [Pool] create new (total=" + (active.size() + 1) + ")");
            } else {
                throw new IllegalStateException("Pool exhausted, maxTotal=" + maxTotal);
            }

            if (testOnBorrow && !factory.validate(obj)) {
                System.out.println("  [Pool] validate failed, destroy and retry");
                factory.destroy(obj);
                continue; // 拿下一个 / 创建新的
            }
            active.add(obj);
            System.out.println("  [Pool] borrow ok, active=" + active.size() + " idle=" + idle.size());
            return obj;
        }
    }

    public synchronized void returnObj(T obj) {
        if (!active.remove(obj)) {
            System.out.println("  [Pool] WARN: returning unknown obj");
            return;
        }
        idle.offer(obj);
        System.out.println("  [Pool] return ok, active=" + active.size() + " idle=" + idle.size());
    }
}
