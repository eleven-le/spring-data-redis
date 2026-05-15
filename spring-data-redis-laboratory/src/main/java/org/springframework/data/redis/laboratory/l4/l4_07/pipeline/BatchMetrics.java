package org.springframework.data.redis.laboratory.l4.l4_07.pipeline;

/**
 * 一次"分批 Pipeline 执行"的指标。
 * <p>
 * 真实业务里它不是简单的 record，而应该接 Micrometer / Prometheus，分批暴露：
 * batch_size、batch_cost_ms、batch_total_count、batch_failed_count。
 * <p>
 * 这里只做最小演示，让学员理解可观测性应该贴在哪一层（BatchExecutor，而不是业务方法内部）。
 */
public record BatchMetrics(int batchSize,
                           int batchCount,
                           int totalItems,
                           long totalCostMillis,
                           int successCount,
                           int failCount) {

    public double avgCostPerBatch() {
        return batchCount == 0 ? 0d : (double) totalCostMillis / batchCount;
    }


    public double opsPerSecond() {
        if (totalCostMillis <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return totalItems * 1000d / totalCostMillis;
    }

    @Override
    public String toString() {
        return "BatchMetrics{batchSize=" + batchSize
                + ", batchCount=" + batchCount
                + ", totalItems=" + totalItems
                + ", totalCost=" + totalCostMillis + "ms"
                + ", success=" + successCount
                + ", fail=" + failCount
                + ", avgPerBatch=" + String.format("%.2f", avgCostPerBatch()) + "ms"
                + ", ops/s=" + String.format("%.2f", opsPerSecond())
                + '}';
    }
}
