package org.springframework.data.redis.laboratory.l4.l4_12.demo05;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模拟"配置主数据 + 全局版本号"。
 *
 * <p>真实生产里这一份主数据可以是：DB 配置表、Redis 主缓存、配置中心（Apollo/Nacos）。
 * 关键是它有一个单调递增的 version——这就是 Pub/Sub 兜底机制的支点。
 */
public class ConfigStore {

    public static class Entry {
        public final String key;
        public final String value;
        public final long version;

        public Entry(String key, String value, long version) {
            this.key = key;
            this.value = value;
            this.version = version;
        }
    }

    private final AtomicLong globalVersion = new AtomicLong(0);
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public Entry put(String key, String value) {
        long v = globalVersion.incrementAndGet();
        Entry e = new Entry(key, value, v);
        store.put(key, e);
        return e;
    }

    public Entry get(String key) {
        return store.get(key);
    }

    /** 用于全量对账：返回 version 大于 sinceVersion 的所有 entry。 */
    public Iterable<Entry> snapshotSince(long sinceVersion) {
        return store.values().stream()
                .filter(e -> e.version > sinceVersion)
                .toList();
    }

    public long currentVersion() {
        return globalVersion.get();
    }
}
