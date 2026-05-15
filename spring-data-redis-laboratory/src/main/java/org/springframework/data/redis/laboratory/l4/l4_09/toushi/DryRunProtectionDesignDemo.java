package org.springframework.data.redis.laboratory.l4.l4_09.toushi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 偷师 Demo 4：高危批处理的 DryRun 保护。
 * <p>
 * 灵感来源：Scan + 批量删除如果 pattern 写错就是删库现场。
 * 任何"可批量、可不可逆、可生产事故"的操作，都应该有 dry-run 模式：
 * <ol>
 *   <li>预演：列出会被影响的 N 个对象（最多 maxKeys 个供肉眼核对）；</li>
 *   <li>命中 pattern 风险（"*"/过宽前缀）时强制走 dry-run；</li>
 *   <li>执行前 log 记录操作者 / 范围 / 数量；</li>
 *   <li>批处理本身仍要 batchSize + sleep + 限速。</li>
 * </ol>
 */
public class DryRunProtectionDesignDemo {

    public enum OperationMode {DRY_RUN, EXECUTE}

    /**
     * 可执行的高危操作抽象。
     */
    public interface DangerousOperation<T> {
        /**
         * 列出受影响对象（不执行）。
         */
        List<T> preview(String pattern, int maxItems);

        /**
         * 真实执行，返回处理数量。
         */
        long execute(String pattern, int batchSize);
    }

    /**
     * Dry-run 报告。
     */
    public static final class DryRunResult<T> {
        public final List<T> sample;
        public final boolean patternRisky;
        public final String warning;

        public DryRunResult(List<T> sample, boolean patternRisky, String warning) {
            this.sample = sample;
            this.patternRisky = patternRisky;
            this.warning = warning;
        }

        @Override
        public String toString() {
            return "DryRunResult{sampleSize=" + sample.size()
                    + ", risky=" + patternRisky + ", warning='" + warning + "'}";
        }
    }

    /**
     * 默认 pattern 风险检测：太宽就标 risky。
     */
    public static final Predicate<String> DEFAULT_PATTERN_RISK = p ->
            p == null || p.isEmpty() || p.equals("*") || p.startsWith("*") || p.length() < 5;

    /**
     * 把"是否 dry-run"包装成执行器。
     */
    public static <T> long run(OperationMode mode, String pattern, int batchSize, int previewSize,
                               DangerousOperation<T> op,
                               Predicate<String> riskCheck) {
        Predicate<String> check = riskCheck == null ? DEFAULT_PATTERN_RISK : riskCheck;
        boolean risky = check.test(pattern);
        if (mode == OperationMode.DRY_RUN || risky) {
            List<T> sample = op.preview(pattern, previewSize);
            DryRunResult<T> result = new DryRunResult<>(sample, risky,
                    risky ? "pattern 命中风险规则，强制 dry-run" : "dry-run 模式");
            System.out.println("[DryRun] " + result + ", sample=" + sample);
            return 0;
        }
        return op.execute(pattern, batchSize);
    }

    public static void main(String[] args) {
        // 模拟：一个"删除某 prefix 下所有 key"的高危操作
        DangerousOperation<String> deleter = new DangerousOperation<>() {
            @Override
            public List<String> preview(String pattern, int maxItems) {
                List<String> ks = new ArrayList<>();
                for (int i = 0; i < Math.min(maxItems, 5); i++) ks.add(pattern + "/key-" + i);
                return ks;
            }

            @Override
            public long execute(String pattern, int batchSize) {
                System.out.println("[EXECUTE] real delete pattern=" + pattern);
                return 1234;
            }
        };

        // 1) dry-run
        run(OperationMode.DRY_RUN, "biz:cache:expired:*", 200, 5, deleter, null);
        // 2) pattern 太宽，强制 dry-run
        run(OperationMode.EXECUTE, "*", 200, 5, deleter, null);
        // 3) 正常执行
        long n = run(OperationMode.EXECUTE, "biz:cache:expired:*", 200, 5, deleter, null);
        System.out.println("[done] processed=" + n);
    }
}
