package org.springframework.data.redis.laboratory.l4.l4_09.hash;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_09.L409Keys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * L4-09 HSCAN 实验：增量遍历单个 Hash 的 field-value。
 * <p>
 * 业务背景：用户行为 Hash {@code l4:09:hash:user:behavior}，field 形如
 * {@code view:{itemId} / like:{itemId} / fav:{itemId}}，长期运行后 Hash 会膨胀到几十万 field，
 * 一次 entries() 全量拉取会瞬时占用大量客户端内存与网络带宽。
 * <p>
 * 断点位置：
 * <ul>
 *   <li>{@code HashOperations.scan(H key, ScanOptions)}</li>
 *   <li>{@code DefaultHashOperations.scan} —— 模板 execute 入口</li>
 *   <li>{@code RedisTemplate.execute(RedisCallback)}</li>
 *   <li>{@code RedisHashCommands.hScan} / {@code LettuceHashCommands.hScan}</li>
 *   <li>{@code Cursor<Map.Entry<HK, HV>>}</li>
 * </ul>
 */
public class L409HashScanLab {

    private final StringRedisTemplate template;
    private final HashOperations<String, String, String> hashOps;

    public L409HashScanLab(StringRedisTemplate template) {
        this.template = template;
        this.hashOps = template.opsForHash();
    }

    /**
     * 准备一个"中等大 Hash"用于断点观察。生产真实大 Hash 通常 10w+ field。
     */
    public void prepareLargeHash(String key, int fieldCount) {
        Map<String, String> bulk = new HashMap<>();
        for (int i = 0; i < fieldCount; i++) {
            String prefix = (i % 3 == 0) ? "view:" : (i % 3 == 1) ? "like:" : "fav:";

            bulk.put(prefix + i, String.valueOf(System.currentTimeMillis() % 100_000 + i));

            if (bulk.size() >= 1000) {
                hashOps.putAll(key, bulk);
                bulk.clear();
            }
        }
        if (!bulk.isEmpty()) {
            hashOps.putAll(key, bulk);
        }
    }

    /**
     * 默认 demo key。
     */
    public String defaultKey() {
        return L409Keys.HASH_DEMO_KEY;
    }

    /**
     * HSCAN 全量增量遍历。
     * 注意：这里写"全量"是指 cursor 走完一轮，不是一次性返回——cursor 期间每轮只拉一小批。
     */
    public List<Map.Entry<String, String>> hscanAll(String key, int count, int maxFields) {
        return hscanWith(key, ScanOptions.scanOptions().count(count).build(), maxFields);
    }

    /**
     * HSCAN field pattern 过滤——按业务前缀只取一类 field（如 view:*）。
     */
    public List<Map.Entry<String, String>> hscanByFieldPattern(String key, String fieldPattern, int count, int maxFields) {
        return hscanWith(key, ScanOptions.scanOptions().match(fieldPattern).count(count).build(), maxFields);
    }

    /**
     * HSCAN 分批处理：每批 batchSize 条调用一次 batchHandler。
     * 不要把所有 field 攒到一个 List 再处理——大 Hash 直接 OOM。
     */
    public long hscanAndProcessInBatches(String key, int count, int batchSize,
                                         Consumer<List<Map.Entry<String, String>>> batchHandler) {
        ScanOptions options = ScanOptions.scanOptions().count(count).build();
        long processed = 0;
        List<Map.Entry<String, String>> buffer = new ArrayList<>(batchSize);
        try (Cursor<Map.Entry<String, String>> cursor = hashOps.scan(key, options)) {
            while (cursor.hasNext()) {
                buffer.add(cursor.next());
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
     * 反例对照：entries() 一次拉全量。
     * 大 Hash 这样调会把所有 field-value 一次返回到客户端 List<Entry>。
     * <b>请勿在生产业务对大 Hash 用 entries</b>——这里仅用于断点对照。
     */
    @Deprecated
    public Map<String, String> compareEntriesVsHScanConcept(String key) {
        // 业务里千万不要这样写。这里调用方需对 Hash 大小有上帝视角才能用。
        return hashOps.entries(key);
    }

    /**
     * 清理 demo Hash。
     */
    public void cleanup(String key) {
        template.delete(key);
    }

    private List<Map.Entry<String, String>> hscanWith(String key, ScanOptions options, int maxFields) {
        List<Map.Entry<String, String>> result = new ArrayList<>();
        try (Cursor<Map.Entry<String, String>> cursor = hashOps.scan(key, options)) {
            while (cursor.hasNext() && result.size() < maxFields) {
                result.add(cursor.next());
            }
        }
        return result;
    }
}
