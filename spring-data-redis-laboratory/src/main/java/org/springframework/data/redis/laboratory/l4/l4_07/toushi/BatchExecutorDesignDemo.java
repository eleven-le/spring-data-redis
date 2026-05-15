package org.springframework.data.redis.laboratory.l4.l4_07.toushi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 偷师 Demo 2：通用"分批执行器"。
 * <p>
 * <b>场景</b>：业务从 DB 查出 5 万订单要一次性写到三方系统。无论是写 Redis、写 MySQL、写 MQ，
 * 还是调外部 HTTP，都需要"分批"和"指标"两件事。
 * <p>
 * <b>偷师点</b>：
 *   1) {@link BatchExecutor} 把分批 / 计时 / 异常吞吐 / 指标统计抽走；
 *   2) 业务（{@link BatchTask}）只关心"这一批怎么处理"；
 *   3) {@link BatchMetrics} 提供可观测的指标对象，便于接 Micrometer。
 * <p>
 * 对照 Pipeline：当业务从"循环 Redis 命令"过渡到"分批 Pipeline"时，最容易写成一坨。
 * 抽出 BatchExecutor 后，pipeline / mq / http 都能复用同一套"分批模板"。
 */
public class BatchExecutorDesignDemo {

    @FunctionalInterface
    public interface BatchTask<I, O> {
        O run(List<I> batch);
    }

    public static class BatchMetrics {
        public int batchSize;
        public int batchCount;
        public int totalItems;
        public int successCount;
        public int failCount;
        public long totalCostMs;

        @Override
        public String toString() {
            return "BatchMetrics{batchSize=" + batchSize
                    + ", batchCount=" + batchCount
                    + ", totalItems=" + totalItems
                    + ", success=" + successCount
                    + ", fail=" + failCount
                    + ", cost=" + totalCostMs + "ms}";
        }
    }

    public static class BatchResult<O> {
        public final List<O> outputs = new ArrayList<>();
        public final List<Throwable> errors = new ArrayList<>();
        public final BatchMetrics metrics = new BatchMetrics();
    }

    public static class BatchExecutor<I, O> {
        private final int batchSize;

        public BatchExecutor(int batchSize) {
            if (batchSize <= 0) throw new IllegalArgumentException("batchSize > 0");
            this.batchSize = batchSize;
        }

        public BatchResult<O> execute(List<I> items, BatchTask<I, O> task) {
            BatchResult<O> result = new BatchResult<>();
            result.metrics.batchSize = batchSize;
            result.metrics.totalItems = items.size();
            for (int from = 0; from < items.size(); from += batchSize) {
                int to = Math.min(from + batchSize, items.size());
                List<I> sub = items.subList(from, to);
                long t = System.currentTimeMillis();
                try {
                    result.outputs.add(task.run(sub));
                    result.metrics.successCount += sub.size();
                } catch (Throwable ex) {
                    result.errors.add(ex);
                    result.metrics.failCount += sub.size();
                } finally {
                    result.metrics.totalCostMs += System.currentTimeMillis() - t;
                    result.metrics.batchCount++;
                }
            }
            return result;
        }
    }

    public static void main(String[] args) {
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < 1234; i++) items.add(i);

        BatchExecutor<Integer, Integer> executor = new BatchExecutor<>(200);
        Function<Integer, Integer> doSomething = x -> x * 2;
        BatchResult<Integer> result = executor.execute(items, batch -> {
            int sum = 0;
            for (Integer i : batch) sum += doSomething.apply(i);
            return sum;
        });
        System.out.println("metrics = " + result.metrics);
        System.out.println("first batch outputs = " + result.outputs.get(0));
    }
}
