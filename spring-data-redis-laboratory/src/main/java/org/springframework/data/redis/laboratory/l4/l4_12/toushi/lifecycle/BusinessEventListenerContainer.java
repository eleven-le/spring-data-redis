package org.springframework.data.redis.laboratory.l4.l4_12.toushi.lifecycle;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 偷师 RedisMessageListenerContainer，写一个仅依赖 Spring 接口、不连 Redis 的
 * 简化版业务监听容器。
 *
 * <p>把 Spring Data Redis 那套抽象拆开看清楚：
 * <table border="1">
 * <tr><th>SDR 概念</th><th>本类对应</th><th>解决什么</th></tr>
 * <tr><td>RedisConnectionFactory</td><td>BlockingQueue source</td><td>消息源抽象</td></tr>
 * <tr><td>Subscription / SubscriptionTask</td><td>pollLoop()</td><td>阻塞拉取</td></tr>
 * <tr><td>channelMapping</td><td>topic2listeners</td><td>topic→listeners 注册表</td></tr>
 * <tr><td>TaskExecutor</td><td>dispatchExecutor</td><td>派发线程池</td></tr>
 * <tr><td>SmartLifecycle</td><td>同名实现</td><td>融入 ApplicationContext 启动顺序</td></tr>
 * <tr><td>InitializingBean</td><td>同名实现</td><td>把可失败的初始化推迟到 Spring init 阶段</td></tr>
 * <tr><td>DisposableBean</td><td>同名实现</td><td>容器关闭时统一释放资源</td></tr>
 * </table>
 *
 * <p>业务场景：风控规则变更广播 / 城市配置变更广播——配上 BlockingQueue 你也能搭一个
 * 进程内的轻量事件总线。
 */
public class BusinessEventListenerContainer
        implements SmartLifecycle, InitializingBean, DisposableBean {

    /** 模拟"消息源"的 BlockingQueue。生产中可换成 Redis、Kafka、RocketMQ。 */
    private final BlockingQueue<BusinessEvent> source = new LinkedBlockingQueue<>();

    /** topic→listeners 注册表，对应 SDR 的 channelMapping。 */
    private final Map<String, Collection<BusinessEventListener>> topic2listeners = new ConcurrentHashMap<>();

    private TaskExecutor dispatchExecutor;
    private boolean manageExecutor = false;

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread pollThread;

    /** 对外消息入口：让外部把事件灌进来。 */
    public void publish(BusinessEvent event) {
        source.offer(event);
    }

    /** 注册：可在容器 start 之前或之后调用。 */
    public void addListener(String topic, BusinessEventListener listener) {
        topic2listeners
                .computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>())
                .add(listener);
    }

    /** 反注册：与 SDR removeMessageListener 同义。 */
    public void removeListener(String topic, BusinessEventListener listener) {
        Collection<BusinessEventListener> list = topic2listeners.get(topic);
        if (list != null) list.remove(listener);
    }

    public void setDispatchExecutor(TaskExecutor dispatchExecutor) {
        this.dispatchExecutor = dispatchExecutor;
    }

    // ===== InitializingBean =====
    @Override
    public void afterPropertiesSet() {
        // 偷师点：能在构造方法里做的事，为什么要放这里？
        // 因为有些依赖（比如这里的默认 TaskExecutor）需要在 Spring 已注入完所有依赖后再决定。
        // 把"可失败的资源准备"推到 init 阶段，便于 Spring 的 bean 校验链路统一管理。
        if (dispatchExecutor == null) {
            manageExecutor = true;
            dispatchExecutor = new SimpleAsyncTaskExecutor("biz-listener-dispatch-");
        }
        initialized.set(true);
    }

    // ===== SmartLifecycle =====
    @Override
    public void start() {
        if (!initialized.get()) {
            throw new IllegalStateException("call afterPropertiesSet first");
        }
        if (running.compareAndSet(false, true)) {
            pollThread = new Thread(this::pollLoop, "biz-listener-poll");
            pollThread.setDaemon(true);
            pollThread.start();
            LogPrinter.print("Container", "started");
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (pollThread != null) pollThread.interrupt();
            LogPrinter.print("Container", "stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** autoStartup=true 是 SDR 容器的默认值，让 ApplicationContext refresh 之后自动 start。 */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * 启动顺序：值越小越早。SDR 的 RedisMessageListenerContainer 也定义了
     * 中间偏后的 phase——含义是：让基础设施 Bean 先就绪、再启监听器。
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    // ===== DisposableBean =====
    @Override
    public void destroy() throws Exception {
        stop();
        if (manageExecutor && dispatchExecutor instanceof DisposableBean) {
            ((DisposableBean) dispatchExecutor).destroy();
        }
        topic2listeners.clear();
        initialized.set(false);
        LogPrinter.print("Container", "destroyed");
    }

    // ===== 拉取循环：对应 SDR 的 SubscriptionTask =====
    private void pollLoop() {
        while (running.get()) {
            try {
                BusinessEvent event = source.poll(200, TimeUnit.MILLISECONDS);
                if (event == null) continue;
                Collection<BusinessEventListener> listeners = topic2listeners.get(event.topic);
                if (listeners == null || listeners.isEmpty()) continue;
                for (BusinessEventListener listener : listeners) {
                    // 派发——异常隔离：一个 listener 抛错不影响其他 listener
                    dispatchExecutor.execute(() -> {
                        try {
                            listener.onEvent(event);
                        } catch (Throwable t) {
                            LogPrinter.print("Container-ERR",
                                    "listener crashed: " + t.getClass().getSimpleName() +
                                            " topic=" + event.topic + " msg=" + t.getMessage());
                        }
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
