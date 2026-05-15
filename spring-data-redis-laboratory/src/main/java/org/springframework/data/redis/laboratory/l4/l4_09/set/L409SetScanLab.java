package org.springframework.data.redis.laboratory.l4.l4_09.set;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_09.L409Keys;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * L4-09 SSCAN 实验：增量遍历单个 Set。
 * <p>
 * 业务：内容点赞用户集合 {@code l4:09:set:like:item}——爆款内容点赞数百万，
 * 一次 members() 拉全部相当于在客户端构造一个超大 Set，应用 GC 抖动严重。
 * <p>
 * 断点位置：
 * <ul>
 *   <li>{@code SetOperations.scan(K, ScanOptions)}</li>
 *   <li>{@code DefaultSetOperations.scan}</li>
 *   <li>{@code RedisSetCommands.sScan} / {@code LettuceSetCommands.sScan}</li>
 *   <li>{@code Cursor<V>}</li>
 * </ul>
 */
public class L409SetScanLab {

    private final StringRedisTemplate template;
    private final SetOperations<String, String> setOps;

    public L409SetScanLab(StringRedisTemplate template) {
        this.template = template;
        this.setOps = template.opsForSet();
    }

    /**
     * 准备一个 demo Set。生产爆款 Set 通常 100w+ member。
     */
    public void prepareLargeSet(String key, int memberCount) {
        List<String> buffer = new ArrayList<>(1000);
        for (int i = 0; i < memberCount; i++) {
            buffer.add("u" + i);
            if (buffer.size() >= 1000) {
                setOps.add(key, buffer.toArray(new String[0]));
                buffer.clear();
            }
        }
        if (!buffer.isEmpty()) {
            setOps.add(key, buffer.toArray(new String[0]));
        }
    }

    public String defaultKey() {
        return L409Keys.SET_DEMO_KEY;
    }

    /**
     * SSCAN 增量遍历。
     */
    public List<String> sscanAll(String key, int count, int maxMembers) {
        return sscanWith(key, ScanOptions.scanOptions().count(count).build(), maxMembers);
    }

    /**
     * SSCAN + member pattern。注：member 通常是 id，pattern 实战较少，按需使用。
     */
    public List<String> sscanByPattern(String key, String pattern, int count, int maxMembers) {
        return sscanWith(key,
                ScanOptions.scanOptions().match(pattern).count(count).build(),
                maxMembers);
    }

    /**
     * SSCAN 分批同步：取一批就处理（同步到下游/写文件/打 Kafka），不要全量收集。
     */
    public long sscanAndProcessInBatches(String key, int count, int batchSize,
                                         Consumer<List<String>> batchHandler) {
        ScanOptions options = ScanOptions.scanOptions().count(count).build();
        long processed = 0;
        List<String> buffer = new ArrayList<>(batchSize);
        // SSCAN 可能重复返回同一 member——下游处理务必幂等
        Set<String> seen = new HashSet<>();
        try (Cursor<String> cursor = setOps.scan(key, options)) {
            while (cursor.hasNext()) {
                String m = cursor.next();
                if (!seen.add(m)) {
                    continue; // 重复，跳过
                }
                buffer.add(m);
                processed++;
                if (buffer.size() >= batchSize) {
                    batchHandler.accept(new ArrayList<>(buffer));
                    buffer.clear();
                }
            }
            if (!buffer.isEmpty()) {
                batchHandler.accept(buffer);
            }
        }
        return processed;
    }

    /**
     * 反例：members() 一次拉全部，请勿在生产对大 Set 用。
     */
    @Deprecated
    public Set<String> compareMembersVsSScanConcept(String key) {
        return setOps.members(key);
    }

    public void cleanup(String key) {
        template.delete(key);
    }

    private List<String> sscanWith(String key, ScanOptions options, int maxMembers) {
        List<String> result = new ArrayList<>();
        try (Cursor<String> cursor = setOps.scan(key, options)) {
            while (cursor.hasNext() && result.size() < maxMembers) {
                result.add(cursor.next());
            }
        }
        return result;
    }
}
