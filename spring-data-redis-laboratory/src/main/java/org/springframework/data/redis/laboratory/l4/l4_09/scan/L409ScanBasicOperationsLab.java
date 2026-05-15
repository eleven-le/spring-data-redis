package org.springframework.data.redis.laboratory.l4.l4_09.scan;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_09.L409Keys;

import java.time.Duration;
import java.util.*;

/**
 * L4-09 Scan keyspace 基础实验。
 * <p>
 * 每个方法独立成实验，断点位置：
 * <ul>
 *   <li>{@code RedisTemplate.scan(ScanOptions)} —— 顶层入口，会调用 executeWithStickyConnection</li>
 *   <li>{@code RedisTemplate.executeWithStickyConnection} —— 看 cursor 期间为什么必须粘在同一条连接</li>
 *   <li>{@code RedisConnection.scan(ScanOptions)} —— byte[] 风格底层入口</li>
 *   <li>{@code LettuceConnection.scan(ScanOptions)} —— Lettuce 适配层</li>
 *   <li>{@code Cursor.hasNext / next} —— 看 cursor 内部如何攒一批、用完再发下一轮 SCAN</li>
 *   <li>{@code Cursor.close} —— 看连接如何归还（Sticky 期间不归还，close 时一并释放）</li>
 * </ul>
 * <p>
 * <b>反复强调（写满了注释）</b>：
 * <ol>
 *   <li>Scan 不是强一致快照——遍历期间新增/删除会让结果不稳定；</li>
 *   <li>Scan 可能返回重复 key——业务自己去重 / 保证幂等；</li>
 *   <li>COUNT 是 hint，不是返回条数保证——不要拿 count 当 page size；</li>
 *   <li>不要把 Cursor 暴露给上层——必须在 DAO 内闭合。</li>
 * </ol>
 */
public class L409ScanBasicOperationsLab {

    /**
     * 单批扫描默认 hint。生产敏感场景 100~500，后台任务 500~2000，超大量级要压测。
     */
    public static final int DEFAULT_COUNT = 500;
    /**
     * 内存保护——scanAndCollectLimited 一次性收集到 List 时的硬上限，防止 OOM。
     */
    public static final int DEFAULT_MAX_KEYS = 5_000;

    private final StringRedisTemplate template;

    public L409ScanBasicOperationsLab(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * 准备 demo 数据：l4:09:scan:demo:{i}，便于 scan 实验。
     * 写少量数据即可（默认 200 条），不要在断点学习里制造大数据。
     */
    public void prepareDemoData(int n, Duration ttl) {
        for (int i = 0; i < n; i++) {
            String key = L409Keys.SCAN_DEMO_PREFIX + i;

            if (Objects.nonNull(ttl) && !ttl.isZero()) {
                template.opsForValue().set(key, "v-" + i, ttl);
            } else {
                template.opsForValue().set(key, "v-" + i);
            }
        }
    }

    /**
     * 实验 1：扫整个 keyspace（pattern=*）。
     * <p>
     * Redis 命令链：SCAN 0 MATCH * COUNT 500 → 多轮 → SCAN 0
     * Spring API：{@code template.scan(options)}
     * <p>
     * 为什么不用 KEYS *：KEYS 是 O(N) 一次扫整个 dict 阻塞主线程，生产禁用。
     * 即使是这里的全 pattern，也务必带 count 让单轮扫描可控。
     * 注意：返回的 keys 数量取决于 keyspace 大小，演示时小心数据量。
     */
    public List<String> scanAllKeys(int maxKeys) {
        ScanOptions options = ScanOptions.scanOptions().count(DEFAULT_COUNT).build();
        return scanWithOptions(options, maxKeys);
    }

    /**
     * <h2>实验 2：按 Pattern 扫描 Redis Key（生产推荐写法）</h2>
     *
     * <p>
     * 这是 Redis SCAN 在生产环境中的标准使用姿势。
     * </p>
     *
     * <h3>对应 Redis 原生命令</h3>
     *
     * <pre>{@code
     * SCAN 0 MATCH pattern COUNT count
     * }</pre>
     *
     * <h3>Spring Data Redis 对应 API</h3>
     *
     * <pre>{@code
     * template.scan(
     * ScanOptions.scanOptions()
     * .match(pattern)
     * .count(count)
     * .build()
     * )
     * }</pre>
     *
     * <h3>为什么生产环境推荐 SCAN，而不是 KEYS？</h3>
     *
     * <table border="1">
     * <tr>
     * <th>命令</th>
     * <th>特点</th>
     * <th>风险</th>
     * </tr>
     * <tr>
     * <td>KEYS *</td>
     * <td>一次性扫描全部 key</td>
     * <td>阻塞 Redis 主线程（危险）</td>
     * </tr>
     * <tr>
     * <td>SCAN</td>
     * <td>渐进式遍历</td>
     * <td>低阻塞，生产推荐</td>
     * </tr>
     * </table>
     *
     * <h3>生产中最重要的原则</h3>
     *
     * <p>
     * <strong>pattern 一定要带业务前缀！</strong>
     * </p>
     *
     * <h4>正确示例</h4>
     *
     * <pre>{@code
     * l4:09:scan:demo:*
     * user:order:*
     * coupon:expired:*
     * }</pre>
     *
     * <h4>错误示例（危险）</h4>
     *
     * <pre>{@code
     * *
     * *user*
     * *order*
     * }</pre>
     *
     * <h3>为什么不能乱用 "*"？</h3>
     *
     * <p>
     * Redis 的 SCAN 并不是：
     * </p>
     *
     * <pre>{@code
     * “直接精准匹配 pattern”
     * }</pre>
     *
     * <p>
     * 而是：
     * </p>
     *
     * <ol>
     * <li>先扫描一批 hash bucket</li>
     * <li>拿到这一批 key</li>
     * <li>再做 MATCH 过滤</li>
     * </ol>
     *
     * <p>
     * 所以如果 pattern 太宽：
     * </p>
     *
     * <pre>{@code
     * MATCH *
     * }</pre>
     *
     * <p>
     * Redis 会：
     * </p>
     *
     * <ul>
     * <li>扫描大量无关 key</li>
     * <li>产生大量无效遍历</li>
     * <li>增加 CPU 消耗</li>
     * <li>影响 Redis 主线程性能</li>
     * </ul>
     *
     * <h3>Redis 工程中的核心思想</h3>
     *
     * <p>
     * Redis 是单线程。
     * </p>
     *
     * <p>
     * 所以：
     * </p>
     *
     * <pre>{@code
     * “减少无意义扫描”
     * =
     * “保护 Redis”
     * }</pre>
     *
     * <h3>参数说明</h3>
     *
     * @param pattern Redis key 匹配规则（必须带业务前缀）
     * @param count   给 Redis 的扫描工作量建议值（不是精确返回条数）
     * @param maxKeys 最多返回多少条 key，防止扫描过量
     * @return 扫描得到的 key 集合
     */
    public List<String> scanByPattern(String pattern, int count, int maxKeys) {
        /* 构造 ScanOptions
         * match(pattern) 指定 Redis MATCH 条件
         * count(count) 指定 Redis COUNT hint, 注意： COUNT 不是“返回多少条”， 而是“建议 Redis 本轮扫描工作量”。*/
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(count).build();
        return scanWithOptions(options, maxKeys);
    }

    /**
     * 实验 3：count 大小演示。
     * <p>
     * count 100：单次工作量小，对 Redis 主线程友好，但需要更多轮次；
     * count 5000：单次工作量大，主线程被占用更久，其它命令排队，可能拖慢业务接口。
     * 实际返回数量可能 < / = / > count，永远不要把 count 当作分页 size。
     */
    public List<String> scanWithCount(String pattern, int count, int maxKeys) {
        return scanByPattern(pattern, count, maxKeys);
    }

    /**
     * 实验 4：try-with-resources 正确姿势。
     * <p>
     * <b>必须</b>用 try-with-resources 让 Cursor 自动 close：
     * <ul>
     *   <li>异常路径下也能释放连接资源（Sticky connection 在 close 时归还）；</li>
     *   <li>避免连接泄漏导致连接池打满；</li>
     *   <li>是 SDR 文档强烈推荐的姿势。</li>
     * </ul>
     */
    public List<String> scanWithTryWithResources(String pattern, int count, int maxKeys) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(count).build();
        List<String> result = new ArrayList<>();
        try (Cursor<String> cursor = template.scan(options)) {
            while (cursor.hasNext()) {
                if (result.size() >= maxKeys) {
                    // 内存保护：达到上限就停，不要一次性收集整个 keyspace 到内存
                    break;
                }
                result.add(cursor.next());
            }
        }
        return result;
    }

    /**
     * 实验 5：内存保护版本——maxKeys 是硬上限。
     * 真实业务里千万不要 cursor.forEachRemaining(list::add)，那是 OOM 的捷径。
     */
    public List<String> scanAndCollectLimited(String pattern, int count, int maxKeys) {
        return scanWithTryWithResources(pattern, count, Math.min(maxKeys, DEFAULT_MAX_KEYS));
    }

    /**
     * 实验 6：Scan 可能返回重复 key 的去重处理。
     * <p>
     * Redis 服务端的 SCAN 在 rehash 期间、bucket 复用时可能让同一 key 在不同轮次重复出现。
     * 业务侧的应对：
     * <ol>
     *   <li>处理动作幂等（最优解）；</li>
     *   <li>Scan 收口时用 Set 去重（适合"找完一批后处理"型任务）；</li>
     *   <li>下游处理器自己持久化已处理 id（适合迁移类任务）。</li>
     * </ol>
     */
    public Set<String> scanAndDeduplicate(String pattern, int count, int maxKeys) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(count).build();
        Set<String> result = new HashSet<>();
        try (Cursor<String> cursor = template.scan(options)) {
            while (cursor.hasNext() && result.size() < maxKeys) {
                result.add(cursor.next());
            }
        }
        return result;
    }

    /**
     * <h2>实验 7：证明 Redis SCAN 的 COUNT 不是“精确返回条数”</h2>
     * 这个实验用于观察：
     * <p>
     * 当我们执行 Redis SCAN 时，传入的 {@code COUNT} 只是一个
     * <strong>hint（建议值）</strong>，并不是 Redis 承诺每轮一定返回的数量。
     * </p>
     * <h3>核心结论</h3>
     * <ul>
     *     <li>{@code COUNT 10} 不代表每次一定返回 10 条。</li>
     *     <li>{@code COUNT 1000} 不代表每次一定返回 1000 条。</li>
     *     <li>Redis 可能返回 0 条，也可能返回远大于 COUNT 的数据。</li>
     *     <li>小集合使用 listpack / intset 编码时，可能一次性返回全部元素。</li>
     * </ul>
     * <h3>工作中一定不要这样写</h3>
     * <pre>{@code
     * // 错误示例：
     * // 不能通过返回数量是否小于 count 来判断扫描结束
     * if (result.size() < count) {
     *     break;
     * }
     * }</pre>
     * <h3>正确心智</h3>
     * <p>
     * <p>
     * SCAN 不是分页查询，COUNT 也不是 pageSize。
     * <p>
     * SCAN 是一种 <strong>渐进式、低阻塞、弱精确</strong> 的遍历方式。
     * <p>
     * 判断是否结束，应该依赖 Cursor 是否结束，而不是依赖本轮返回数量。
     * </p>
     *
     * @param pattern   Redis key 匹配规则，例如 {@code user:*}
     * @param count     给 Redis 的 COUNT 建议值，例如 {@code 10} 或 {@code 1000}
     * @param maxRounds 最多观察多少个统计轮次，防止数据太多导致实验跑太久
     * @return 每个观察轮次中，客户端实际消费到的元素数量
     */
    public List<Integer> demonstrateCountIsHint(String pattern, int count, int maxRounds) {
        /* 1. 构造 SCAN 参数
         * 对应 Redis 原生命令： SCAN cursor MATCH pattern COUNT count
         * 例如： SCAN 0 MATCH user:* COUNT 10
         * 注意： 这里的 count 不是“每次返回 10 条”， 而是“建议 Redis 本轮扫描时，工作量大概按 10 来”。*/
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(count).build();

        /* 2. 记录每个观察轮次实际消费到的元素数量
         * 例如返回： [20, 20, 7]
         * 表示：  第一段消费了 20 个； 第二段消费了 20 个； 最后一段消费了 7 个。
         * 注意： 这里记录的是客户端消费统计值， 不是 Redis 服务端真实每一轮 SCAN 返回数量。*/
        List<Integer> roundSizes = new ArrayList<>();

        /* 3. 打开 Redis Cursor
         * template.scan(options) 底层会不断执行 Redis SCAN。
         * Cursor 可以理解为： Redis 服务端游标 + Spring 客户端迭代器。
         * 使用 try-with-resources 是为了自动关闭 Cursor，避免 Redis 连接资源泄漏。*/
        try (Cursor<String> cursor = template.scan(options)) {
            int currentRound = 0;  /* currentRound： 当前观察轮次内，客户端已经消费了多少个 key。 注意：它不等于 Redis 服务端的一轮 SCAN。 */
            int rounds = 0;   /* rounds：已经记录了多少个观察轮次。 maxRounds 是安全限制，防止 Redis 中数据量过大时，实验方法一直扫描。*/

            /* 4. 开始遍历 Cursor
             * cursor.hasNext()： 表示当前 Cursor 是否还有元素可以消费。
             * rounds < maxRounds：表示最多只观察指定轮次，避免实验时间过长。*/
            while (cursor.hasNext() && rounds < maxRounds) {
                /* 5. 消费一个元素
                 * 这里的 cursor.next() 是从客户端 Cursor 中取出一个 key。
                 * 注意：一次 next() 不等于一次 Redis SCAN。Spring Data Redis 内部可能已经提前从 Redis 拉回了一批数据。*/
                cursor.next();
                currentRound++;   /*当前观察轮次消费数量 +1*/

                /* 6. 人为划分一个观察轮次
                 * 这里用 count * 2 作为观察窗口。
                 * 为什么不是 count？ 因为我们要证明 COUNT 不是精确返回条数，所以这里故意用一个更宽的窗口观察实际消费情况。
                 * 注意：这不是 Redis 原生 SCAN 的一轮。这是我们为了实验观察，人为定义的一段统计区间。*/
                if (currentRound >= count * 2) {
                    roundSizes.add(currentRound);
                    currentRound = 0;  /*重置当前观察轮次计数*/
                    rounds++;  /* 已完成观察轮次 +1*/
                }
            }
            /* 7. 处理最后不足一个观察窗口的数据
             * 例如：count = 10  count * 2 = 20
             * 前面已经记录了： [20, 20]
             * 最后还剩 7 个，那么这里把 7 也记录下来。 */
            if (currentRound > 0) {
                roundSizes.add(currentRound);
            }
        }
        /* 8. 返回观察结果
         * 通过返回值你可以看到： COUNT 并不是一个严格的返回条数控制参数。
         * 工作中不要依赖：“本轮返回数量 == count” 这种逻辑。 */
        return roundSizes;
    }

    /**
     * 实验 8：演示 Scan 不是分页 API。
     * <p>
     * 想象一个错误用法："给我第 2 页 100 条"——SCAN 没有 page 概念，cursor 是不透明 token，
     * 不能 cursor=100 跳到第 100 个元素。下次调用必须用上一次返回的 cursor。
     * 真要给用户分页（榜单 / 列表），用 ZRANGE / ZREVRANGE / ZRANGEBYSCORE，不要用 ZSCAN。
     */
    public void demonstrateScanIsNotPagination() {
        // 这里只输出思路，不写"看似可行但实际错误"的代码——避免误导后来读者
        System.out.println("[L409] SCAN 不是分页：cursor 是服务器端不透明 token，"
                + "不能跳转、不能基于 offset，重复轮次后才会回到 0；想做分页请用 ZRANGE。");
    }

    /**
     * 实验后清理。
     */
    public void cleanupDemoData(int n) {
        List<String> keys = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            keys.add(L409Keys.SCAN_DEMO_PREFIX + i);
        }
        template.delete(keys);
    }

    private List<String> scanWithOptions(ScanOptions options, int maxKeys) {
        List<String> result = new ArrayList<>();
        try (Cursor<String> cursor = template.scan(options)) {
            while (cursor.hasNext()) {
                if (result.size() >= maxKeys) {
                    break;
                }
                result.add(cursor.next());
            }
        }
        return result;
    }
}
