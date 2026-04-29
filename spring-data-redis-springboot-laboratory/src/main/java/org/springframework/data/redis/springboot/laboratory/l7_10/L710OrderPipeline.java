package org.springframework.data.redis.springboot.laboratory.l7_10;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class L710OrderPipeline implements InitializingBean, DisposableBean {

    private final L710FlashSaleProperties properties;

    private final BlockingQueue<L710OrderTask> queue = new LinkedBlockingQueue<>();

    private final Map<String, L710OrderRecord> acceptedOrders = new ConcurrentHashMap<>();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "l7-10-order-worker");
        thread.setDaemon(true);
        return thread;
    });

    public L710OrderPipeline(L710FlashSaleProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        this.worker.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    L710OrderTask task = this.queue.take();
                    TimeUnit.MILLISECONDS.sleep(this.properties.getOrderWorkerDelayMs());
                    this.acceptedOrders.computeIfPresent(task.getOrderNo(), (ignored, existing) ->
                            existing.withStatus("PERSISTED", Instant.now()));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    @Override
    public void destroy() {
        this.worker.shutdownNow();
    }

    public void submit(L710OrderTask task) {
        Objects.requireNonNull(task, "task must not be null");
        Instant acceptedAt = Instant.now();
        this.acceptedOrders.put(task.getOrderNo(),
                new L710OrderRecord(task.getOrderNo(), task.getSkuId(), task.getRound(), task.getUserId(),
                        "QUEUED", acceptedAt, null));
        this.queue.offer(task);
    }

    public List<L710OrderRecord> getOrdersForSku(long skuId) {
        return this.acceptedOrders.values().stream()
                .filter(record -> record.getSkuId() == skuId)
                .sorted(Comparator.comparing(L710OrderRecord::getAcceptedAt).reversed())
                .collect(Collectors.toList());
    }

    public int getQueueSize() {
        return this.queue.size();
    }

    public static class L710OrderTask {

        private final String orderNo;
        private final long skuId;
        private final long round;
        private final String userId;

        public L710OrderTask(String orderNo, long skuId, long round, String userId) {
            this.orderNo = orderNo;
            this.skuId = skuId;
            this.round = round;
            this.userId = userId;
        }

        public String getOrderNo() {
            return this.orderNo;
        }

        public long getSkuId() {
            return this.skuId;
        }

        public long getRound() {
            return this.round;
        }

        public String getUserId() {
            return this.userId;
        }
    }

    public static class L710OrderRecord {

        private final String orderNo;
        private final long skuId;
        private final long round;
        private final String userId;
        private final String status;
        private final Instant acceptedAt;
        private final Instant persistedAt;

        public L710OrderRecord(String orderNo, long skuId, long round, String userId,
                               String status, Instant acceptedAt, Instant persistedAt) {
            this.orderNo = orderNo;
            this.skuId = skuId;
            this.round = round;
            this.userId = userId;
            this.status = status;
            this.acceptedAt = acceptedAt;
            this.persistedAt = persistedAt;
        }

        public L710OrderRecord withStatus(String nextStatus, Instant nextPersistedAt) {
            return new L710OrderRecord(this.orderNo, this.skuId, this.round, this.userId,
                    nextStatus, this.acceptedAt, nextPersistedAt);
        }

        public String getOrderNo() {
            return this.orderNo;
        }

        public long getSkuId() {
            return this.skuId;
        }

        public long getRound() {
            return this.round;
        }

        public String getUserId() {
            return this.userId;
        }

        public String getStatus() {
            return this.status;
        }

        public Instant getAcceptedAt() {
            return this.acceptedAt;
        }

        public Instant getPersistedAt() {
            return this.persistedAt;
        }
    }
}
