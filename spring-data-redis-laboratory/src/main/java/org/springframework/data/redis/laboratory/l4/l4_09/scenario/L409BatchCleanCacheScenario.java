package org.springframework.data.redis.laboratory.l4.l4_09.scenario;

import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_09.L409Keys;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 场景 1：批量清理过期缓存。
 * <p>
 * <b>业务故事</b>：定时任务凌晨 3 点清理 {@code l4:09:clean:product:detail:old:*}。
 * 老代码图省事写 KEYS pattern + DEL —— 即便凌晨 QPS 低，也会让 Redis 主线程卡 1~3 秒，
 * 期间所有同实例业务命令排队，海外用户接口超时。正解：SCAN + 分批 UNLINK + 限速 + dry-run。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>maxKeys 硬上限：保护客户端内存与 Redis 主线程总占用时长；</li>
 *   <li>batchSize：单批 DEL 控制在 100~500，避免大命令；</li>
 *   <li>批间 sleep：给主线程喘息；</li>
 *   <li>UNLINK 优先于 DEL：异步释放内存，对大 key 友好（Redis 4.0+）；</li>
 *   <li>dryRun 模式：上线前先看会清多少 key，避免 pattern 写错全库被清。</li>
 * </ul>
 */
public class L409BatchCleanCacheScenario {

    private final StringRedisTemplate template;

    public L409BatchCleanCacheScenario(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * Scan 找一批 key，仅找不删——dry-run / 预演用。
     */
    public List<String> scanKeys(String pattern, int count, int maxKeys) {
        guardPattern(pattern);
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(count).build();
        List<String> result = new ArrayList<>(Math.min(maxKeys, 1024));
        try (Cursor<String> cursor = template.scan(options)) {
            while (cursor.hasNext() && result.size() < maxKeys) {
                result.add(cursor.next());
            }
        }
        return result;
    }

    /**
     * 真正的"扫到一批就删一批"——不要先全量收集再删。
     * 返回值是已删除 key 数量。
     */
    public long cleanByScanAndDelete(String pattern, int count, int batchSize, long sleepBetweenBatchMs) {
        guardPattern(pattern);
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(count).build();
        long deleted = 0;
        List<String> buffer = new ArrayList<>(batchSize);
        try (Cursor<String> cursor = template.scan(options)) {
            while (cursor.hasNext()) {
                buffer.add(cursor.next());
                if (buffer.size() >= batchSize) {
                    deleted += deleteBatch(buffer, false);
                    buffer.clear();
                    sleepQuietly(sleepBetweenBatchMs);
                }
            }
            if (!buffer.isEmpty()) {
                deleted += deleteBatch(buffer, false);
            }
        }
        return deleted;
    }

    /**
     * 分批 UNLINK 版本——4.0+ Redis 推荐。SDR 提供 {@code template.unlink(Collection)}。
     */
    public long cleanByScanAndUnlinkIfSupported(String pattern, int count, int batchSize, long sleepBetweenBatchMs) {
        guardPattern(pattern);
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(count).build();
        long unlinked = 0;
        List<String> buffer = new ArrayList<>(batchSize);
        try (Cursor<String> cursor = template.scan(options)) {
            while (cursor.hasNext()) {
                buffer.add(cursor.next());
                if (buffer.size() >= batchSize) {
                    unlinked += deleteBatch(buffer, true);
                    buffer.clear();
                    sleepQuietly(sleepBetweenBatchMs);
                }
            }
            if (!buffer.isEmpty()) {
                unlinked += deleteBatch(buffer, true);
            }
        }
        return unlinked;
    }

    /**
     * Dry run：只扫不删，返回前 maxKeys 个匹配的 key 给运维肉眼核对。
     */
    public List<String> dryRunClean(String pattern, int count, int maxKeys) {
        return scanKeys(pattern, count, maxKeys);
    }

    /**
     * 准备一些 demo key 给本场景使用。
     */
    public void prepareDemoData(int n, Duration ttl) {
        for (int i = 0; i < n; i++) {
            String k = L409Keys.CLEAN_DEMO_PREFIX + "product:detail:old:" + i;
            if (ttl != null && !ttl.isZero()) {
                template.opsForValue().set(k, "v-" + i, ttl);
            } else {
                template.opsForValue().set(k, "v-" + i);
            }
        }
    }

    /**
     * 直接全部清理 demo 数据，仅用于实验后清场。
     */
    public long clearDemoData() {
        return cleanByScanAndUnlinkIfSupported(L409Keys.CLEAN_DEMO_PREFIX + "*", 500, 200, 0L);
    }

    /**
     * <h2>批量删除 Redis Key（支持 UNLINK / DEL 自动降级）</h2>
     *
     * <p>
     * 这是一个非常典型的生产级 Redis 删除封装。
     * </p>
     *
     * <h3>核心能力</h3>
     *
     * <ul>
     * <li>支持批量删除</li>
     * <li>支持 UNLINK 异步删除</li>
     * <li>兼容低版本 Redis 自动降级</li>
     * <li>避免 Redis 主线程阻塞</li>
     * </ul>
     *
     * <h3>对应 Redis 原生命令</h3>
     *
     * <table border="1">
     * <tr>
     * <th>Spring API</th>
     * <th>Redis 命令</th>
     * <th>特点</th>
     * </tr>
     * <tr>
     * <td>template.delete(keys)</td>
     * <td>DEL key1 key2 ...</td>
     * <td>同步删除（阻塞主线程）</td>
     * </tr>
     * <tr>
     * <td>template.unlink(keys)</td>
     * <td>UNLINK key1 key2 ...</td>
     * <td>异步删除（推荐生产）</td>
     * </tr>
     * </table>
     *
     * <h3>DEL vs UNLINK（超级重要）</h3>
     *
     * <h4>DEL</h4>
     *
     * <pre>{@code
     * DEL bigKey
     * }</pre>
     *
     * <p>
     * Redis 主线程会：
     * </p>
     *
     * <ol>
     * <li>立即释放内存</li>
     * <li>同步回收数据结构</li>
     * <li>直到释放完成才继续处理下一个命令</li>
     * </ol>
     *
     * <p>
     * 如果是大 Key：
     * </p>
     *
     * <ul>
     * <li>大 Hash</li>
     * <li>大 List</li>
     * <li>大 Set</li>
     * <li>百万元素 ZSet</li>
     * </ul>
     *
     * <p>
     * 会导致 Redis 主线程卡顿。
     * </p>
     *
     * <h4>UNLINK（生产推荐）</h4>
     *
     * <pre>{@code
     * UNLINK bigKey
     * }</pre>
     *
     * <p>
     * Redis 主线程只做：
     * </p>
     *
     * <ol>
     * <li>断开 key 引用</li>
     * <li>把真正内存回收丢给后台线程</li>
     * </ol>
     *
     * <p>
     * 所以：
     * </p>
     *
     * <pre>{@code
     * UNLINK = 非阻塞删除
     * }</pre>
     *
     * <h3>为什么要做自动降级？</h3>
     *
     * <p>
     * 因为：
     * </p>
     *
     * <pre>{@code
     * UNLINK 是 Redis 4.0 才引入的
     * }</pre>
     *
     * <p>
     * 老版本 Redis 不支持：
     * </p>
     *
     * <pre>{@code
     * ERR unknown command 'UNLINK'
     * }</pre>
     *
     * <p>
     * 所以生产代码必须：
     * </p>
     *
     * <ul>
     * <li>优先尝试 UNLINK</li>
     * <li>失败自动降级 DEL</li>
     * </ul>
     *
     * <h3>互联网大厂里的真实经验</h3>
     *
     * <p>
     * 大 Key 删除事故是 Redis 高频线上事故之一。
     * </p>
     *
     * <p>
     * 很多人：
     * </p>
     *
     * <pre>{@code
     * DEL user:feed:123456
     * }</pre>
     *
     * <p>
     * 结果：
     * </p>
     *
     * <ul>
     * <li>Redis CPU 飙升</li>
     * <li>请求 RT 抖动</li>
     * <li>整个实例卡顿</li>
     * </ul>
     *
     * <p>
     * 所以现在成熟团队：
     * </p>
     *
     * <pre>{@code
     * 能 UNLINK 就绝不 DEL 大 Key
     * }</pre>
     *
     * @param keys   要删除的 Redis key 集合
     * @param unlink true = 优先使用 UNLINK；false = 使用 DEL
     * @return 实际删除成功的 key 数量
     */
    private long deleteBatch(List<String> keys, boolean unlink) {
        if (keys.isEmpty()) return 0;
        try {
            Long n;
            if (unlink) {
                n = template.unlink(keys);
            } else {
                n = template.delete(keys);
            }
            return n == null ? 0 : n;
        } catch (InvalidDataAccessApiUsageException e) {
            // 老 Redis 不支持 UNLINK 时降级为 DEL
            Long n = template.delete(keys);
            return n == null ? 0 : n;
        }
    }

    private static void guardPattern(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("pattern 不能为空");
        }
        if (!pattern.startsWith(L409Keys.ROOT)) {
            // 实验项目硬约束：清理只允许在本章 prefix 下，避免误清生产 key
            throw new IllegalArgumentException(
                    "L409 清理场景的 pattern 必须以 " + L409Keys.ROOT + " 开头：" + pattern);
        }
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
