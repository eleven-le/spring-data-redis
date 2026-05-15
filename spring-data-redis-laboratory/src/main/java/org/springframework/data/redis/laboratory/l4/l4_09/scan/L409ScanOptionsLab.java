package org.springframework.data.redis.laboratory.l4.l4_09.scan;

import org.springframework.data.redis.core.ScanOptions;
import org.springframework.util.StringUtils;

/**
 * L4-09 ScanOptions 专题实验。
 * <p>
 * ScanOptions 是参数对象（builder 风格），把 MATCH / COUNT / TYPE 收口到一个 immutable 对象。
 * 这是 SDR 一贯的偷师素材：参数膨胀就提对象，避免方法签名爆炸。
 * <p>
 * 断点入口：
 * <ul>
 *   <li>{@code ScanOptions.scanOptions()} —— 拿 builder</li>
 *   <li>{@code ScanOptions.ScanOptionsBuilder.match / count / build}</li>
 *   <li>{@code ScanOptions.NONE} —— 默认空 options（无 match 全 keyspace）</li>
 * </ul>
 */
public class L409ScanOptionsLab {

    /**
     * 只配置 match。count 不传时 SDR 沿用 Redis 默认（约 10），单轮工作量极小但轮次极多。
     */
    public ScanOptions buildMatchOptions(String pattern) {
        guardPattern(pattern);
        return ScanOptions.scanOptions().match(pattern).build();
    }

    /**
     * match + count 标准姿势。生产代码尽量两个都给，让单轮工作量可预测。
     */
    public ScanOptions buildMatchAndCountOptions(String pattern, long count) {
        guardPattern(pattern);
        if (count <= 0) {
            throw new IllegalArgumentException("count 必须 > 0，实际：" + count);
        }
        return ScanOptions.scanOptions().match(pattern).count(count).build();
    }

    /**
     * 演示 pattern 风险——pattern="*" 会让 SCAN 在整个 keyspace 上扫，
     * 即便 SCAN 不阻塞主线程，整体扫描时长依然按 keyspace 总量算。
     * <p>
     * 真实业务中：
     * <ul>
     *   <li>"l4:09:scan:demo:*" —— 范围窄，浪费少；</li>
     *   <li>"l4:09:*"          —— 整章命中，可接受；</li>
     *   <li>"*"                —— 整库扫描，风险点；</li>
     *   <li>"user:*:profile"   —— 中间通配符，每轮服务器端 MATCH 都要走 glob，CPU 抖动大。</li>
     * </ul>
     */
    public ScanOptions demonstratePatternRisk(boolean dangerous) {
        if (dangerous) {
            return ScanOptions.scanOptions().match("*").count(500).build(); // 学习用：构造一个"全库扫描"options 仅为对照——不要在生产业务里这样写
        }
        return ScanOptions.scanOptions().match("l4:09:scan:demo:*").count(500).build();
    }

    /**
     * 演示 count 取舍：
     * <ul>
     *   <li>count=10：每轮极小，主线程友好，但轮次多；适合接口路径上的探查；</li>
     *   <li>count=5000：每轮工作量大，主线程被占久，但轮次少；适合后台任务且经过压测。</li>
     * </ul>
     * 任何选择都不能取代"压测 + 监控 + 限速"。
     */
    public ScanOptions demonstrateCountTradeoff(boolean aggressive) {
        long count = aggressive ? 5000 : 100;
        return ScanOptions.scanOptions().match("l4:09:*").count(count).build();
    }

    private static void guardPattern(String pattern) {
        if (!StringUtils.hasLength(pattern)) {
            throw new IllegalArgumentException("pattern 不能为空");
        }
        if ("*".equals(pattern)) {
            System.err.println("[L409] WARN: pattern='*' 等于全 keyspace 扫描，在 demo 之外建议拒绝该 pattern。"); // 不直接抛——学习场景允许，但调用方必须意识到这是反例
        }
    }
}
