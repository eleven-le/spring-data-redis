package org.springframework.data.redis.laboratory.l4.l4_06.reliability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_06.L406Keys;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 05.03 ACK 与 Pending 恢复
 *
 * 真实场景：
 *  消费者实例 A 拉到一条消息但还没 ACK 就宕机；
 *  消息留在 Group 的 PEL（Pending Entries List）里，没人 ACK 永远不会 redeliver。
 *  必须有"巡检 + 接管"机制。
 *
 * 本类只做"扫描"：
 *  - XPENDING group → 看总量、min/max id、idle 最长的 consumer；
 *  - XPENDING group IDLE - + count → 列出超过指定 idle 时间的具体消息。
 *
 * 关键 Spring Data Redis API：
 *  - {@link StreamOperations#pending(Object, String)} → XPENDING group
 *  - {@link StreamOperations#pending(Object, String, Range, long)} → XPENDING group IDLE - + COUNT
 *
 * 建议断点：
 *  - DefaultStreamOperations#pending —— 看怎么映射到 RedisStreamCommands；
 *  - LettuceStreamCommands#xPending —— 真正下发到 Lettuce。
 *
 * 新手避坑：
 *  - 一次性 pending(... , Long.MAX_VALUE)：PEL 大时一把梭撑爆客户端；务必分页 / 设上限；
 *  - 把 idle 阈值设得过短（几秒）：业务还在正常处理就被别人 claim 走，反而引发幂等冲突；
 *  - 忽略 PendingMessagesSummary：失去对"PEL 是否在堆积"的整体感知。
 */
@Component
public class PendingMessageScanner {

    /** 单次扫描返回的最大消息数，避免一次拉太多。 */
    public static final long DEFAULT_BATCH = 100;

    private final StringRedisTemplate redis;

    @Autowired
    public PendingMessageScanner(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 总量视图：业务总 pending、最早/最晚 id、各 consumer 的 pending 数。 */
    public PendingMessagesSummary summary(String group) {
        return redis.opsForStream().pending(L406Keys.STREAM_ORDER_EVENT, group);
    }

    /**
     * 列出 idle 超过 minIdle 的具体 pending 消息。
     * 实现：先 pending(... Range.unbounded() ...) 拿到候选，再客户端二次过滤 idle 时间。
     * 注：SDR 2.7.x 的 PendingMessages 暴露的是单条 PendingMessage 的 idle 时间。
     */
    public List<PendingMessage> listIdleOver(String group, Duration minIdle) {
        return listIdleOver(group, minIdle, DEFAULT_BATCH);
    }

    public List<PendingMessage> listIdleOver(String group, Duration minIdle, long batchLimit) {
        StreamOperations<String, Object, Object> ops = redis.opsForStream();
        PendingMessages page = ops.pending(L406Keys.STREAM_ORDER_EVENT, group, Range.unbounded(), batchLimit);

        List<PendingMessage> result = new ArrayList<>();
        for (PendingMessage pm : page) {
            if (pm.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0) {
                result.add(pm);
            }
        }
        return result;
    }
}
