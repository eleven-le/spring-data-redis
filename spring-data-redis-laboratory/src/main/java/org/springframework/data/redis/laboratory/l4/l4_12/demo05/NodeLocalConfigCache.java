package org.springframework.data.redis.laboratory.l4.l4_12.demo05;

import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点本地配置缓存。除了 key→value 还要记录"我已经同步到的最大 version"，
 * 这是节点重启后做对账的指纹。
 */
public class NodeLocalConfigCache {

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

    private final String nodeName;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private volatile long lastAppliedVersion = 0;

    public NodeLocalConfigCache(String nodeName) {
        this.nodeName = nodeName;
    }

    public boolean putIfNewer(String key, String value, long version) {
        boolean[] applied = {false};
        cache.compute(key, (k, e) -> {
            if (e == null || version > e.version) {
                applied[0] = true;
                return new Entry(key, value, version);
            }
            return e;
        });
        if (applied[0] && version > lastAppliedVersion) {
            lastAppliedVersion = version;
        }
        return applied[0];
    }

    public long getLastAppliedVersion() { return lastAppliedVersion; }

    public String getNodeName() { return nodeName; }

    public void printSnapshot() {
        LogPrinter.print("Cache-" + nodeName, "lastAppliedVersion=" + lastAppliedVersion);
        cache.forEach((k, e) -> LogPrinter.print("Cache-" + nodeName,
                k + "=" + e.value + " v=" + e.version));
    }
}
