package org.springframework.data.redis.laboratory.l4.l4_06.reliability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_06.L406Keys;
import org.springframework.data.redis.laboratory.l4.l4_06.consumer.AbstractOrderEventStreamListener;
import org.springframework.data.redis.laboratory.l4.l4_06.dlq.DeadLetterStreamPublisher;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.BizErrorType;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEvent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 05.03 ACK 与 Pending 恢复
 *
 * 真实场景：
 *  服务实例 A 在拉取后宕机，主 stream 里若干 messageId 的 owner 还指着 A。
 *  容器没有 A 这个 consumer 来 ACK，消息永远悬挂。
 *  调度任务定时跑这个 RecoveryService：
 *
 *    1. {@link PendingMessageScanner} 扫出 idle &gt; threshold 的候选；
 *    2. {@link PendingMessageReclaimer} XCLAIM 给"复活线程"；
 *    3. 拿到 record 重新走业务回调；
 *    4. 重试次数 &gt;= maxRetry → 直接 DLQ + ACK，避免毒丸；
 *    5. 业务成功 → ACK。
 *
 * 关键 Spring Data Redis API：
 *  - 见 {@link PendingMessageScanner} 与 {@link PendingMessageReclaimer}
 *  - {@code StreamOperations#acknowledge}
 *
 * 新手避坑：
 *  - 调度任务不限并发：多个实例同时 claim 同一批，争抢消耗连接；可加分布式锁；
 *  - 不区分"刚 claim 成功"与"接管后业务又失败"：要分别处理；
 *  - 不写 DLQ 兜底：毒丸消息每次 claim 都失败，PEL 永远治不好；
 *  - 不重置 retryCount：恢复时 retry 计数应当 +1，否则永远低于阈值。
 */
@Component
public class PendingMessageRecoveryService {

    /** 默认 idle 阈值：5 分钟。 */
    public static final Duration DEFAULT_IDLE_THRESHOLD = Duration.ofMinutes(5);
    /** 默认最大重试次数：超过即 DLQ。 */
    public static final int DEFAULT_MAX_RETRY = 5;

    private final StringRedisTemplate redis;
    private final PendingMessageScanner scanner;
    private final PendingMessageReclaimer reclaimer;
    private final DeadLetterStreamPublisher dlq;

    @Autowired
    public PendingMessageRecoveryService(StringRedisTemplate redis,
                                         PendingMessageScanner scanner,
                                         PendingMessageReclaimer reclaimer,
                                         DeadLetterStreamPublisher dlq) {
        this.redis = redis;
        this.scanner = scanner;
        this.reclaimer = reclaimer;
        this.dlq = dlq;
    }

    /**
     * 对指定 group 跑一轮恢复：
     *  - 扫描 idle 超过阈值的 pending；
     *  - claim 给 recoveryConsumer；
     *  - 通过 redeliverer 走业务回调（一般直接 listener::onMessage）；
     *  - 业务回调内部决定 ACK / DLQ。
     */
    public RecoveryReport recover(String group,
                                  String recoveryConsumer,
                                  Duration idleThreshold,
                                  int maxRetry,
                                  AbstractOrderEventStreamListener listener) {
        RecoveryReport report = new RecoveryReport(group);

        List<PendingMessage> idle = scanner.listIdleOver(group, idleThreshold);
        report.scanned = idle.size();
        if (idle.isEmpty()) {
            return report;
        }

        List<MapRecord<String, String, String>> claimed =
                reclaimer.claim(group, recoveryConsumer, idleThreshold, idle);
        report.claimed = claimed.size();

        for (MapRecord<String, String, String> record : claimed) {
            Map<String, String> body = record.getValue();
            int retryCount = parseInt(body.get("retryCount"), 0) + 1;

            if (retryCount > maxRetry) {
                // 强制 DLQ + ACK，治愈毒丸。
                String eventId = body.getOrDefault("eventId", "unknown");
                try {
                    OrderEvent event = AbstractOrderEventStreamListener.parse(body);
                    event.setRetryCount(retryCount);
                    dlq.publish(event, record.getId().getValue(), group, recoveryConsumer,
                            BizErrorType.POISONOUS,
                            "Pending recover超过最大重试 " + maxRetry,
                            "请人工排查 eventId=" + eventId);
                } catch (Exception parseEx) {
                    dlq.publishRaw(body, record.getId().getValue(), group, recoveryConsumer,
                            "Pending recover & parse failed: " + parseEx.getMessage(),
                            "请人工修复消息体");
                }
                redis.opsForStream().acknowledge(L406Keys.STREAM_ORDER_EVENT, group, record.getId());
                report.dlqAcked++;
                continue;
            }

            // 注入新的 retryCount，让 listener 内部模板继续判定。
            // 这里采用"包装一份新 MapRecord 重投"的简化方式：实测中也可以直接调 listener.onMessage。
            Map<String, String> newBody = new java.util.LinkedHashMap<>(body);
            newBody.put("retryCount", String.valueOf(retryCount));
            MapRecord<String, String, String> redelivered = MapRecord
                    .create(record.getStream(), newBody)
                    .withId(record.getId());

            listener.onMessage(redelivered);
            report.redelivered++;
        }
        return report;
    }

    /** 默认参数版本。 */
    public RecoveryReport recover(String group,
                                  String recoveryConsumer,
                                  AbstractOrderEventStreamListener listener) {
        return recover(group, recoveryConsumer, DEFAULT_IDLE_THRESHOLD, DEFAULT_MAX_RETRY, listener);
    }

    private static int parseInt(String s, int dft) {
        if (s == null || s.isEmpty()) return dft;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return dft;
        }
    }

    public static class RecoveryReport {
        public final String group;
        public int scanned;
        public int claimed;
        public int redelivered;
        public int dlqAcked;

        public RecoveryReport(String group) {
            this.group = group;
        }

        @Override
        public String toString() {
            return "RecoveryReport{group='" + group + "', scanned=" + scanned
                    + ", claimed=" + claimed
                    + ", redelivered=" + redelivered
                    + ", dlqAcked=" + dlqAcked + '}';
        }
    }
}
