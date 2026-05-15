package org.springframework.data.redis.laboratory.l4.l4_06.dlq;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_06.L406Keys;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 06.06 死信队列 DLQ
 *
 * 真实场景：
 *  人工补偿 / 离线脚本需要从 DLQ 读最近 N 条死信，结合错误信息判断：
 *   - 是哪个 group 的哪个 consumer 失败的？
 *   - errorType 是参数非法（人工修） 还是 RETRYABLE（重发到原 stream 即可）？
 *   - 失败时刻、retryCount 分布是否有异常聚集？
 *
 * 关键 Spring Data Redis API：
 *  - {@link StreamOperations#range(Object, Range)} —— XRANGE 同步读取一段消息；
 *  - {@link StreamOperations#size(Object)} —— XLEN 看 DLQ 长度。
 *
 * 建议断点：
 *  - DefaultStreamOperations#range —— 看 XRANGE 怎么映射 RecordId 区间；
 *  - LettuceStreamCommands#xRange —— 真正下到驱动；
 *  - 解析返回 List&lt;MapRecord&gt; 的字段对照 Publisher 的写入。
 *
 * 新手避坑：
 *  - 直接 XRANGE - +：DLQ 大时一次性返回全量，撑爆客户端内存；
 *  - 不分页：用 count 限制单次返回条数；
 *  - DLQ 上挂 group 自动消费：会形成 ping-pong 循环，禁止。
 */
@Component
public class DeadLetterMessageInspector {

    private final StringRedisTemplate redis;

    @Autowired
    public DeadLetterMessageInspector(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Long size() {
        return redis.opsForStream().size(L406Keys.STREAM_ORDER_EVENT_DLQ);
    }

    /** 读最近 limit 条 DLQ 消息（XRANGE - + COUNT limit）。 */
    public List<MapRecord<String, String, String>> peek(int limit) {
        StreamOperations<String, Object, Object> ops = redis.opsForStream();
        @SuppressWarnings({"unchecked", "rawtypes"})
        List<MapRecord<String, String, String>> records = (List) ops.range(
                L406Keys.STREAM_ORDER_EVENT_DLQ,
                Range.unbounded(),
                RedisZSetCommands.Limit.limit().count(limit));
        return records;
    }
}
