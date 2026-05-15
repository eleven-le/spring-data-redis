package org.springframework.data.redis.laboratory.l4.l4_09.compare;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 对比：Scan vs Pipeline——两个解决不同问题的工具，组合使用。
 * <p>
 * <ul>
 *   <li>Scan：增量发现 key（解决"找哪些 key"）；</li>
 *   <li>Pipeline：一次 RTT 批量执行命令（解决"如何高效执行 N 条命令"）。</li>
 * </ul>
 * <p>
 * 不要混用心智：Pipeline 不是"找 key"工具；Scan 也不是"批量执行器"。
 */
public class L409ScanVsPipelineCompare {

    private final StringRedisTemplate template;

    public L409ScanVsPipelineCompare(StringRedisTemplate template) {
        this.template = template;
    }

    /** Scan 找 key：返回 pattern 命中的前 maxKeys 个 key。 */
    public List<String> scanFindKeys(String pattern, int count, int maxKeys) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(count).build();
        List<String> result = new ArrayList<>();
        try (Cursor<String> cursor = template.scan(options)) {
            while (cursor.hasNext() && result.size() < maxKeys) {
                result.add(cursor.next());
            }
        }
        return result;
    }

    /** Pipeline 处理 key：对一批已知 key 一次 RTT 批量 GET。 */
    public List<Object> pipelineProcessKeys(List<String> keys) {
        return template.executePipelined((RedisCallback<Object>) conn -> {
            for (String k : keys) {
                conn.stringCommands().get(k.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
    }
}
