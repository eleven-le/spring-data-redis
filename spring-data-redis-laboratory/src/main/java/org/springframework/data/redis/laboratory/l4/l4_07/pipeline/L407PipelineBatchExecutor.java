package org.springframework.data.redis.laboratory.l4.l4_07.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 通用"分批执行器"。
 * <p>
 * 业务最常踩的坑是：把几万条命令一次性塞进 executePipelined，
 * 结果客户端内存暴涨、Redis 输出缓冲打爆、单个调用耗时被拉到秒级。
 * <p>
 * 这个执行器把"分批 + 指标"这两个责任从业务里抽出来：
 * - 业务只关心"每一批怎么处理"（batchFunction）；
 * - 执行器负责"按 batchSize 切分、累计耗时、捕获异常"。
 * <p>
 * 真实业务里需要再追加：
 * 1) 限速器（RateLimiter / Bucket4j），保护 Redis；
 * 2) 失败重试（最多 N 次、指数回退）；
 * 3) 关键指标埋点（Micrometer）；
 * 4) 失败补偿队列（MQ / DB）。
 *
 * @param <I> 输入元素类型，例如商品 ID
 * @param <O> 单批返回结果（通常 List&lt;Object&gt;，业务后处理）
 */
public class L407PipelineBatchExecutor<I, O> {

    private final int batchSize;

    public L407PipelineBatchExecutor(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须 > 0");
        }
        if (batchSize > 5000) {
            // 软警告：5000 是经验上限，不强制阻止
            System.err.println("[L407PipelineBatchExecutor] 警告: batchSize=" + batchSize + " 偏大，请确认压测过；推荐 100~2000。");
        }
        this.batchSize = batchSize;
    }

    /**
     * 不收集 metrics 的简单版本。
     */
    public BatchResult<O> executeInBatches(List<I> items,
                                           Function<List<I>, O> batchFunction) {
        BatchResult<O> result = new BatchResult<>();
        if (items == null || items.isEmpty()) {
            return result;
        }

        for (int from = 0; from < items.size(); from += batchSize) {
            int to = Math.min(from + batchSize, items.size());
            List<I> sub = items.subList(from, to);
            long start = System.currentTimeMillis();
            try {
                O batchOut = batchFunction.apply(sub);
                List<O> single = new ArrayList<>(1);
                single.add(batchOut);
                result.appendRawResults(single);
                result.incSuccess(sub.size());
            } catch (Throwable t) {
                result.appendError(t);
                result.incFail(sub.size());
            } finally {
                result.addCost(System.currentTimeMillis() - start);
            }
        }
        return result;
    }

    /**
     * 收集 metrics 的完整版本。
     */
    public BatchMetrics executeInBatchesWithMetrics(List<I> items,
                                                    Function<List<I>, O> batchFunction) {
        if (items == null || items.isEmpty()) {
            return new BatchMetrics(batchSize, 0, 0, 0, 0, 0);
        }

        BatchResult<O> result = executeInBatches(items, batchFunction);
        int batchCount = (items.size() + batchSize - 1) / batchSize;
        return new BatchMetrics(
                batchSize,
                batchCount,
                items.size(),
                result.totalCostMillis(),
                result.successCount(),
                result.failCount()
        );
    }

    public int batchSize() {
        return batchSize;
    }
}
