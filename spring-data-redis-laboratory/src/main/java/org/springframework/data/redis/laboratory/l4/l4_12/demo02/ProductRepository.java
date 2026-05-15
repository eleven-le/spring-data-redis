package org.springframework.data.redis.laboratory.l4.l4_12.demo02;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 主数据仓库（DB / Redis 主缓存的内存模拟）。
 *
 * <p>表达的核心架构观点：
 * Pub/Sub 是通知，主数据才是事实来源。Listener 收到广播后，必须用 productId 来这里
 * 回查最新数据，而不是直接相信消息体里的 version / 字段。
 */
public class ProductRepository {

    public static class ProductRow {
        public long productId;
        public long shopId;
        public String name;
        public long priceCent;
        public boolean onShelf;
        public long version;

        public ProductRow copy() {
            ProductRow r = new ProductRow();
            r.productId = productId;
            r.shopId = shopId;
            r.name = name;
            r.priceCent = priceCent;
            r.onShelf = onShelf;
            r.version = version;
            return r;
        }
    }

    private final Map<Long, AtomicReference<ProductRow>> table = new ConcurrentHashMap<>();

    public void seed(long productId, long shopId, String name, long priceCent) {
        ProductRow row = new ProductRow();
        row.productId = productId;
        row.shopId = shopId;
        row.name = name;
        row.priceCent = priceCent;
        row.onShelf = true;
        row.version = 1L;
        table.put(productId, new AtomicReference<>(row));
    }

    /**
     * 模拟一次后台编辑：原子提升版本号并修改字段。
     * 返回新版本号，调用方据此发布广播。
     */
    public long updatePrice(long productId, long newPriceCent) {
        AtomicReference<ProductRow> ref = table.get(productId);
        if (ref == null) throw new IllegalStateException("no such product: " + productId);
        ProductRow updated;
        ProductRow current;
        do {
            current = ref.get();
            updated = current.copy();
            updated.priceCent = newPriceCent;
            updated.version = current.version + 1;
        } while (!ref.compareAndSet(current, updated));
        return updated.version;
    }

    public ProductRow findById(long productId) {
        AtomicReference<ProductRow> ref = table.get(productId);
        return ref == null ? null : ref.get().copy();
    }
}
