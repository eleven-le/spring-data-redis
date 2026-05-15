package org.springframework.data.redis.laboratory.l4.l4_03.toushi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 偷师 4：Bound Operations —— 把"key/资源"绑定到一个领域对象。
 * <p>
 * RedisTemplate 提供 BoundValueOperations / BoundHashOperations，把 key 绑住，
 * 这样调用方就不用每次方法都重复传 key，写出来的代码更像"操作一个领域对象"，
 * 而不像"反复给同一个 key 发命令"。
 * <p>
 * 业务上这套模式特别适合"某个用户的某种资源"：
 * userCart.add(skuA, 1);   // ✅ 看起来就是在操作"这个用户的购物车"
 * cartOps.add(userId, skuA, 1); // ❌ 多余的 userId 出现在每行
 */
public class BoundResourceOperationsDemo {

    /** 非绑定版：每个方法都要带 userId。 */
    static class UserCartOperations {
        private final Map<String, Map<String, Long>> store = new LinkedHashMap<>();

        public void add(String userId, String skuId, long qty) {
            store.computeIfAbsent(userId, k -> new LinkedHashMap<>()).merge(skuId, qty, Long::sum);
        }
        public Map<String, Long> getCart(String userId) {
            return store.getOrDefault(userId, new LinkedHashMap<>());
        }
        public void clear(String userId) { store.remove(userId); }
    }

    /** 绑定版：key 已经被 capture，调用方写起来像在操作领域对象。 */
    static class BoundUserCartOperations {
        private final String userId;
        private final UserCartOperations delegate;

        BoundUserCartOperations(String userId, UserCartOperations delegate) {
            this.userId = userId;
            this.delegate = delegate;
        }

        public void add(String skuId, long qty) { delegate.add(userId, skuId, qty); }
        public Map<String, Long> entries()      { return delegate.getCart(userId); }
        public void clear()                     { delegate.clear(userId); }

        @Override public String toString() { return "Cart(" + userId + ")"; }
    }

    public static void main(String[] args) {
        UserCartOperations cartOps = new UserCartOperations();

        // 非绑定版：每行都要带 userId，业务噪音重
        cartOps.add("u-1001", "SKU-A", 1);
        cartOps.add("u-1001", "SKU-B", 2);
        cartOps.add("u-1001", "SKU-A", 1);
        System.out.println("[非绑定] " + cartOps.getCart("u-1001"));

        // 绑定版：业务代码读起来就是"操作这个用户的购物车"
        BoundUserCartOperations userCart = new BoundUserCartOperations("u-2002", cartOps);
        userCart.add("SKU-X", 3);
        userCart.add("SKU-Y", 5);
        System.out.println("[绑定]   " + userCart + " -> " + userCart.entries());
        userCart.clear();
    }
}
