package org.springframework.data.redis.laboratory.l3_08.toushi.resource_holder_template;

import java.util.HashMap;
import java.util.Map;

/**
 * <h3>🥷 简化版 Resource Holder Template</h3>
 *
 * <p>用 70 行复刻 {@code RedisConnectionUtils} + {@code RedisConnectionHolder} 的核心思想:
 * ThreadLocal 持有 + 引用计数 + finally 释放。</p>
 *
 * <h4>对照 SDR 源码</h4>
 * <ul>
 *   <li>{@code MiniResourceHolderTemplate#get}       ↔ {@code RedisConnectionUtils#doGetConnection}</li>
 *   <li>{@code MiniResourceHolderTemplate#release}    ↔ {@code RedisConnectionUtils#releaseConnection}</li>
 *   <li>{@code Holder#requested/released}             ↔ {@code ResourceHolderSupport} 同名方法</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class MiniResourceHolderTemplate {

    /** ThreadLocal 持有所有资源(key=资源标识,value=Holder)。Spring 用的是 TransactionSynchronizationManager。*/
    private static final ThreadLocal<Map<String, Holder>> THREAD_LOCAL =
            ThreadLocal.withInitial(HashMap::new);

    /** 借资源:已存在则 ref++,不存在则新建 */
    public static String get(String key) {
        Map<String, Holder> map = THREAD_LOCAL.get();
        Holder holder = map.get(key);
        if (holder != null) {
            holder.requested();
            System.out.println("  [Holder] reuse '" + key + "' ref → " + holder.refCount);
            return holder.resource;
        }
        Holder fresh = new Holder("RES-" + System.nanoTime());
        fresh.requested();
        map.put(key, fresh);
        System.out.println("  [Holder] create '" + key + "' ref → " + fresh.refCount);
        return fresh.resource;
    }

    /** 还资源:ref-- 减到 0 才真关 */
    public static void release(String key) {
        Map<String, Holder> map = THREAD_LOCAL.get();
        Holder holder = map.get(key);
        if (holder == null) return;
        holder.released();
        System.out.println("  [Holder] release '" + key + "' ref → " + holder.refCount);
        if (holder.refCount == 0) {
            map.remove(key);
            System.out.println("  [Holder] really close '" + key + "' = " + holder.resource);
        }
    }

    /** 简化版的 Spring ResourceHolderSupport */
    static class Holder {
        final String resource;
        int refCount = 0;
        Holder(String r) { this.resource = r; }
        void requested() { refCount++; }
        void released()  { refCount--; }
    }
}
