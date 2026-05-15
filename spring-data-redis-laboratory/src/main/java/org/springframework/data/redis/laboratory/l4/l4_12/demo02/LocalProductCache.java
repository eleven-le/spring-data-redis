package org.springframework.data.redis.laboratory.l4.l4_12.demo02;

import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点本地缓存（Caffeine / 自研 LocalCache 的最简模拟）。
 *
 * <p>带版本号的 putIfNewer 是关键：
 * 多节点并发时，不同节点收到的广播顺序可能错乱（Pub/Sub 不保序、不去重）。
 * 业务层必须靠 version 比较防止"旧值覆盖新值"。
 */
public class LocalProductCache {

    public static class CachedProduct {
        public final long productId;
        public final String name;
        public final long priceCent;
        public final boolean onShelf;
        public final long version;

        public CachedProduct(long productId, String name, long priceCent, boolean onShelf, long version) {
            this.productId = productId;
            this.name = name;
            this.priceCent = priceCent;
            this.onShelf = onShelf;
            this.version = version;
        }
    }

    private final String nodeName;
    private final Map<Long, CachedProduct> cache = new ConcurrentHashMap<>();

    public LocalProductCache(String nodeName) {
        this.nodeName = nodeName;
    }

    public CachedProduct get(long productId) {
        return cache.get(productId);
    }

    /**
     * 仅当 incoming.version > 当前 version（或当前不存在）时才覆盖。
     * 这是一切"通知 + 回查 + 本地缓存"模式的幂等基石。
     */
    public boolean putIfNewer(CachedProduct incoming) {
        boolean[] applied = {false};
        cache.compute(incoming.productId, (k, existing) -> {
            if (existing == null || incoming.version > existing.version) {
                applied[0] = true;
                return incoming;
            }
            return existing;
        });
        return applied[0];
    }

    public void evict(long productId) {
        cache.remove(productId);
    }

    public void printSnapshot() {
        cache.forEach((id, p) -> LogPrinter.print("Cache-" + nodeName,
                "productId=" + id + " priceCent=" + p.priceCent + " v=" + p.version));
    }

    public String getNodeName() { return nodeName; }
}
