package org.springframework.data.redis.laboratory.l4.l4_07.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次分批 Pipeline 执行的"汇总结果"。
 * <p>
 * 真实业务里：
 * - successCount / failCount 用于打 metric；
 * - errors 用于落日志和告警；
 * - rawResults 用于业务后处理（比如把 100 个商品 ID 的 GET 结果合并）。
 * <p>
 * 不要把 errors 当作"补偿队列"使用——补偿是另一个责任，应该有自己的存储和重试策略。
 */
public class BatchResult<T> {

    private int successCount;
    private int failCount;
    private long totalCostMillis;
    private final List<T> rawResults = new ArrayList<>();
    private final List<Throwable> errors = new ArrayList<>();

    public void incSuccess(int delta) {
        this.successCount += delta;
    }

    public void incFail(int delta) {
        this.failCount += delta;
    }

    public void addCost(long delta) {
        this.totalCostMillis += delta;
    }

    public void appendRawResults(List<T> results) {
        if (results != null) {
            this.rawResults.addAll(results);
        }
    }

    public void appendError(Throwable t) {
        this.errors.add(t);
    }

    public int successCount() {
        return successCount;
    }

    public int failCount() {
        return failCount;
    }

    public long totalCostMillis() {
        return totalCostMillis;
    }

    public List<T> rawResults() {
        return rawResults;
    }

    public List<Throwable> errors() {
        return errors;
    }

    @Override
    public String toString() {
        return "BatchResult{success=" + successCount
                + ", fail=" + failCount
                + ", cost=" + totalCostMillis + "ms"
                + ", errors=" + errors.size()
                + '}';
    }
}
