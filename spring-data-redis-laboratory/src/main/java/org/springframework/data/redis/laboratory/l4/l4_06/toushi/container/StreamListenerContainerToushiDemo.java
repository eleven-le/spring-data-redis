package org.springframework.data.redis.laboratory.l4.l4_06.toushi.container;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 偷师 → Listener Container
 *
 * 偷师对象：{@code StreamMessageListenerContainer + StreamPollTask + Subscription}
 *
 * 它解决了什么问题：
 *  - 业务不该自己写 while-true 阻塞拉取；
 *  - 不该自己管线程、订阅生命周期、异常处理、动态扩缩；
 *  - "容器"把这些基础设施沉下去，业务只剩 onMessage。
 *
 * 在 C 端业务里如何采纳：
 *  - 多源数据汇聚（DB binlog + Kafka + 内部事件 + 第三方 webhook）做统一调度；
 *  - 容器维护源 → listener 的注册关系；
 *  - poll 任务在指定线程池上跑，每个源独立轮询不互相阻塞；
 *  - 异常打日志 + 上报，不让 poll 任务静默死。
 *
 * 这里写一个领域无关的"轻量监听容器"骨架，验证 SDR 的设计完全可以迁移。
 */
public class StreamListenerContainerToushiDemo {

    public static class Source<T> {
        public final String name;
        public final Queue<T> buffer = new ConcurrentLinkedQueue<>();

        public Source(String name) {
            this.name = name;
        }
    }

    public static class Subscription {
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        public void cancel() {
            cancelled.set(true);
        }

        public boolean isActive() {
            return !cancelled.get();
        }
    }

    public static class GenericListenerContainer<T> {

        private final ExecutorService executor;
        private final Consumer<Throwable> errorHandler;
        private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
        private volatile boolean started = false;

        public GenericListenerContainer(int poolSize, Consumer<Throwable> errorHandler) {
            this.executor = Executors.newFixedThreadPool(poolSize, r -> {
                Thread t = new Thread(r, "toushi-listener-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            });
            this.errorHandler = errorHandler;
        }

        /** 把 source / listener 注册成一个 Subscription，返回控制句柄。 */
        public Subscription receive(Source<T> source, Consumer<T> listener) {
            Subscription sub = new Subscription();
            subscriptions.add(sub);
            executor.submit(() -> pollLoop(sub, source, listener));
            return sub;
        }

        private void pollLoop(Subscription sub, Source<T> source, Consumer<T> listener) {
            // ↑ 对应 StreamPollTask#doLoop —— while !cancelled 阻塞拉取。
            while (sub.isActive() && started) {
                try {
                    T item = source.buffer.poll();
                    if (item == null) {
                        Thread.sleep(50);
                        continue;
                    }
                    listener.accept(item);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable th) {
                    // ↑ 对应 SDR ErrorHandler：异常不要重新抛出，否则 poll 任务终止。
                    errorHandler.accept(th);
                }
            }
        }

        public void start() {
            started = true;
        }

        public void stop() {
            started = false;
            subscriptions.forEach(Subscription::cancel);
            executor.shutdownNow();
        }
    }

    public static void main(String[] args) throws Exception {
        Source<String> orderEvents = new Source<>("order-events");
        Source<String> stockEvents = new Source<>("stock-events");

        GenericListenerContainer<String> container = new GenericListenerContainer<>(
                4, ex -> System.err.println("[toushi-container] err " + ex));
        container.start();

        Subscription s1 = container.receive(orderEvents, msg ->
                System.out.println("[orders] got: " + msg));
        Subscription s2 = container.receive(stockEvents, msg ->
                System.out.println("[stock] got: " + msg));

        for (int i = 0; i < 3; i++) {
            orderEvents.buffer.add("order-" + i);
            stockEvents.buffer.add("stock-" + i);
        }
        Thread.sleep(500);

        s1.cancel();
        s2.cancel();
        container.stop();
        System.out.println("[toushi-container] stopped");
    }
}
