package org.springframework.data.redis.laboratory.l4.l4_12.demo05;

import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;

/**
 * 兜底扫描器：节点上线后或周期性触发，按 lastAppliedVersion 增量补齐主数据。
 *
 * <p>真实生产做法：
 * - 应用启动后立即跑一次（保证启动期遗漏的广播被补回来）
 * - 配置 ScheduledExecutorService 每 N 分钟跑一次（保证长时间网络抖动后能收敛）
 * - 高频热点配置可以缩短到 30s
 *
 * <p>这一段就是"Pub/Sub + 兜底对账"组合拳的关键——
 * 它让你敢于把 Pub/Sub 用于实时通知，又不会因为偶尔丢消息导致业务长期不一致。
 */
public class ReconciliationScanner {

    private final NodeLocalConfigCache cache;
    private final ConfigStore store;

    public ReconciliationScanner(NodeLocalConfigCache cache, ConfigStore store) {
        this.cache = cache;
        this.store = store;
    }

    public void runOnce() {
        long since = cache.getLastAppliedVersion();
        long target = store.currentVersion();
        LogPrinter.print("Reconcile-" + cache.getNodeName(),
                "scan since=" + since + " target=" + target);
        int count = 0;
        for (ConfigStore.Entry e : store.snapshotSince(since)) {
            if (cache.putIfNewer(e.key, e.value, e.version)) {
                count++;
            }
        }
        LogPrinter.print("Reconcile-" + cache.getNodeName(),
                "fixed=" + count + " newLastApplied=" + cache.getLastAppliedVersion());
    }
}
