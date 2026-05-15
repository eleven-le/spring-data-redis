package org.springframework.data.redis.laboratory.l4.l4_09.toushi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 偷师 Demo 3：BatchScanProcessor —— "扫一批，处理一批"。
 * <p>
 * 灵感来源：L4-09 Scan + Pipeline 的标准结构——cursor 不停往外吐 key，每攒到 batchSize 就交给 handler 处理，
 * handler 处理完清空 buffer，继续下一轮。
 * <p>
 * 抽象出来后，业务里很多场景能复用：
 * <ul>
 *   <li>扫数据库分页 + 批量 sink 到 ES；</li>
 *   <li>扫 Kafka 消息 + 批量调三方；</li>
 *   <li>扫 OSS 对象列表 + 批量删除。</li>
 * </ul>
 */
public class BatchScanProcessorDesignDemo {

    /** 数据源抽象——任何能"按批吐数据"的源。 */
    public interface ScanSource<T> {
        boolean hasNext();
        T next();
        void close();
    }

    /** 批处理器抽象——业务把"对一批做什么"塞进来。 */
    public interface BatchHandler<T> { void handle(List<T> batch); }

    /** 监控指标——结构跟 Pipeline 章的 BatchMetrics 故意保持一致。 */
    public static final class ProcessMetrics {
        public final AtomicLong scanned    = new AtomicLong();
        public final AtomicLong processed  = new AtomicLong();
        public final AtomicLong batches    = new AtomicLong();
        public final AtomicLong failed     = new AtomicLong();
        @Override public String toString() {
            return "ProcessMetrics{scanned=" + scanned + ", processed=" + processed
                    + ", batches=" + batches + ", failed=" + failed + "}";
        }
    }

    /** 通用执行器。 */
    public static <T> ProcessMetrics process(ScanSource<T> source, int batchSize,
                                             BatchHandler<T> handler,
                                             long maxItems,
                                             Consumer<Exception> onError) {
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize > 0");
        ProcessMetrics m = new ProcessMetrics();
        List<T> buffer = new ArrayList<>(batchSize);
        try {
            while (source.hasNext() && m.scanned.get() < maxItems) {
                buffer.add(source.next());
                m.scanned.incrementAndGet();
                if (buffer.size() >= batchSize) {
                    flush(buffer, handler, m, onError);
                }
            }
            if (!buffer.isEmpty()) flush(buffer, handler, m, onError);
        } finally {
            source.close();
        }
        return m;
    }

    private static <T> void flush(List<T> buffer, BatchHandler<T> handler,
                                   ProcessMetrics m, Consumer<Exception> onError) {
        try {
            handler.handle(new ArrayList<>(buffer));
            m.processed.addAndGet(buffer.size());
            m.batches.incrementAndGet();
        } catch (Exception e) {
            m.failed.addAndGet(buffer.size());
            if (onError != null) onError.accept(e);
            // 不抛——任务可重跑
        } finally {
            buffer.clear();
        }
    }

    public static void main(String[] args) {
        // 模拟一个"吐 250 个元素"的 source
        ScanSource<Integer> source = new ScanSource<>() {
            int i = 0;
            @Override public boolean hasNext() { return i < 250; }
            @Override public Integer next() { return i++; }
            @Override public void close() { /* release */ }
        };

        ProcessMetrics m = process(source, 50, batch -> {
            // 模拟批处理：调三方 / 写 ES / 打 Kafka
        }, Long.MAX_VALUE, e -> System.err.println("batch failed: " + e));

        System.out.println(m);
    }
}
