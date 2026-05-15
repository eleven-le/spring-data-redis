package org.springframework.data.redis.laboratory.l4.l4_06.reliability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.RecordId;
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
 *  扫描出"idle 超阈值"的消息后，把它们 claim 给当前 consumer。
 *  claim 之后这些 record 重新交给 listener 跑一遍业务。
 *
 * 关键 Spring Data Redis API：
 *  - {@link StreamOperations#claim(Object, String, String, Duration, RecordId...)} → XCLAIM
 *  - SDR 2.7.x 的 StreamOperations 接口未直接暴露 XAUTOCLAIM；
 *    所以本类的实现是"先 pending → 再 claim"，与 XAUTOCLAIM 等效但更可控。
 *
 * XPENDING / XCLAIM / XAUTOCLAIM 三命令对照（务必背下来）：
 *  - XPENDING：只查询 PEL，不改归属；
 *  - XCLAIM：把指定 messageId 的 owner 切到当前 consumer，可读出最新 record；
 *    入参 minIdleTime 在服务端做"原子化二次校验"，避免 ABA 抢错；
 *  - XAUTOCLAIM (Redis 6.2+)：原子化"扫描 + 切归属"。
 *    SDR 2.7.x 在驱动层支持，但 StreamOperations 没有便利方法暴露，需要 RedisStreamCommands 直接调。
 *
 * 建议断点：
 *  - DefaultStreamOperations#claim → LettuceStreamCommands#xClaim；
 *  - 看返回的 List&lt;MapRecord&gt; 字段，对照 Pending 扫描结果。
 *
 * 新手避坑：
 *  - claim 之后立刻 ACK：业务还没真正跑完就 ACK，等于跳过；
 *  - 把 minIdle 设成 0：撞上正常处理中的消息会抢错；
 *  - 不限制 retryCount：claim → 失败 → claim → 失败 永远循环，必须配合 DLQ。
 */
@Component
public class PendingMessageReclaimer {

    private final StringRedisTemplate redis;

    @Autowired
    public PendingMessageReclaimer(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 把一组 PendingMessage 切到当前 consumer。
     *
     * @param group        目标 group
     * @param toConsumer   接管人
     * @param minIdle      服务端二次校验：只接管 idle &gt;= minIdle 的消息
     * @param pendings     之前由 Scanner 扫到的候选
     * @return 真正被 claim 成功的 record 列表
     */
    public List<MapRecord<String, String, String>> claim(String group,
                                                         String toConsumer,
                                                         Duration minIdle,
                                                         List<PendingMessage> pendings) {
        if (pendings.isEmpty()) {
            return List.of();
        }
        RecordId[] ids = new RecordId[pendings.size()];
        for (int i = 0; i < pendings.size(); i++) {
            ids[i] = pendings.get(i).getId();
        }
        StreamOperations<String, Object, Object> ops = redis.opsForStream();
        @SuppressWarnings({"unchecked", "rawtypes"})
        List<MapRecord<String, String, String>> claimed = new ArrayList<>(
                (List) ops.claim(L406Keys.STREAM_ORDER_EVENT, group, toConsumer, minIdle, ids));
        return claimed;
    }
}
