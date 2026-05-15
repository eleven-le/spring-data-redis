package org.springframework.data.redis.laboratory.l4.l4_06.monitor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_06.L406Keys;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 06.07 积压监控
 *
 * 真实场景：
 *  C 端高并发下，消费侧任何"卡一下"都会让 PEL / lag 飙升。
 *  上线前必须把这套指标接到 Micrometer / Prometheus / Grafana：
 *
 *  - stream 长度 → 趋势性增长是泄漏信号；
 *  - last-generated-id / first-entry-id → 时间窗口的近似（id 由毫秒生成）；
 *  - 每个 group 的 pending 数 → "处理不过来"的直接信号；
 *  - 每个 group 的 lastDeliveredId → 与 last-generated-id 之差就是 group lag；
 *  - DLQ 长度 → 治理优先级；
 *  - oldest pending idle → "毒丸"或"消费者宕机"信号；
 *  - 各 consumer 的 pending 分布 → 实例健康度。
 *
 * 关键 Spring Data Redis API：
 *  - {@link StreamOperations#info(Object)} → XINFO STREAM
 *  - {@link StreamOperations#groups(Object)} → XINFO GROUPS
 *  - {@link StreamOperations#pending(Object, String)} → XPENDING summary
 *  - {@link StreamOperations#size(Object)} → XLEN
 *
 * 建议断点：
 *  - DefaultStreamOperations#info / groups —— 看怎么把 List&lt;Object&gt; 映射成 XInfoStream / XInfoGroups；
 *  - LettuceStreamCommands#xInfo* —— 真正下发到 Lettuce。
 *
 * 新手避坑：
 *  - 只看 stream 长度不看 group lag：积压不在 stream 长度上，而在消费者跟不上；
 *  - 不监控 oldest pending idle：消费者宕机后第一时间没人发现；
 *  - 不监控 DLQ 长度：DLQ 偷偷涨，没人处理 → 失败消息堆成山。
 */
@Component
public class StreamQueueMetricsReporter {

    private final StringRedisTemplate redis;

    private static final List<String> ALL_GROUPS = List.of(
            L406Keys.GROUP_INVENTORY,
            L406Keys.GROUP_COUPON,
            L406Keys.GROUP_NOTIFY,
            L406Keys.GROUP_RISK,
            L406Keys.GROUP_ANALYTICS
    );

    @Autowired
    public StreamQueueMetricsReporter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 一站式快照：业务上线前 / 报警时调一次即可看清现状。 */
    public Map<String, Object> snapshot() {
        StreamOperations<String, Object, Object> ops = redis.opsForStream();
        Map<String, Object> snapshot = new LinkedHashMap<>();

        snapshot.put("streamKey", L406Keys.STREAM_ORDER_EVENT);
        snapshot.put("streamLength", ops.size(L406Keys.STREAM_ORDER_EVENT));
        snapshot.put("dlqLength", ops.size(L406Keys.STREAM_ORDER_EVENT_DLQ));

        try {
            StreamInfo.XInfoStream info = ops.info(L406Keys.STREAM_ORDER_EVENT);
            snapshot.put("lastGeneratedId", info.lastGeneratedId());
            snapshot.put("firstEntryId", info.firstEntryId());
            snapshot.put("lastEntryId", info.lastEntryId());
            snapshot.put("groupCount", info.groupCount());
        } catch (Exception ex) {
            snapshot.put("info_error", ex.getMessage());
        }

        Map<String, Object> groupViews = new LinkedHashMap<>();
        for (String group : ALL_GROUPS) {
            Map<String, Object> view = new LinkedHashMap<>();
            try {
                PendingMessagesSummary summary = ops.pending(L406Keys.STREAM_ORDER_EVENT, group);
                view.put("totalPending", summary.getTotalPendingMessages());
                view.put("minMessageId", summary.minMessageId());
                view.put("maxMessageId", summary.maxMessageId());
                view.put("pendingPerConsumer", summary.getPendingMessagesPerConsumer());
            } catch (Exception ex) {
                view.put("pending_error", ex.getMessage());
            }
            groupViews.put(group, view);
        }
        snapshot.put("groups", groupViews);

        try {
            StreamInfo.XInfoGroups groups = ops.groups(L406Keys.STREAM_ORDER_EVENT);
            Map<String, Object> groupInfo = new LinkedHashMap<>();
            // 偷师点：XInfoGroups 不实现 Iterable，但暴露了 forEach / iterator() / stream()，
            // 用 forEach 一行解决。
            groups.forEach(g -> {
                Map<String, Object> gv = new LinkedHashMap<>();
                gv.put("consumerCount", g.consumerCount());
                gv.put("pendingCount", g.pendingCount());
                gv.put("lastDeliveredId", g.lastDeliveredId());
                groupInfo.put(g.groupName(), gv);
            });
            snapshot.put("xinfoGroups", groupInfo);
        } catch (Exception ex) {
            snapshot.put("xinfo_groups_error", ex.getMessage());
        }
        return snapshot;
    }

    /** 简易 stdout 打印，调试用。生产请接 Micrometer。 */
    public void printSnapshot() {
        Map<String, Object> snap = snapshot();
        snap.forEach((k, v) -> System.out.println("[L406][metrics] " + k + " = " + v));
    }
}
