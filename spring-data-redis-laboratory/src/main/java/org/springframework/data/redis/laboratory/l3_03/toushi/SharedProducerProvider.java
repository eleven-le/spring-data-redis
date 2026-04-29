package org.springframework.data.redis.laboratory.l3_03.toushi;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 偷师 LettuceConnectionProvider：
 *   - 把"获取生产者"封装成 Provider 接口
 *   - 共享策略 vs 专用策略由具体 Provider 实现
 *   - 业务方完全感知不到底层是 lazy create、池化、还是新建
 * <p>
 * 这里实现的是"全局共享策略"：
 *   - 整个 JVM 只 new 一次具体 MqProducer
 *   - 偷师 Lettuce SharedConnection 的 lazy + double-check
 */
public class SharedProducerProvider {

    private final Supplier<MqProducer> producerFactory;
    private final AtomicReference<MqProducer> ref = new AtomicReference<>();
    private final Object lock = new Object();

    public SharedProducerProvider(Supplier<MqProducer> producerFactory) {
        this.producerFactory = producerFactory;
    }

    public MqProducer get() {
        MqProducer p = ref.get();
        if (p != null) return p;
        synchronized (lock) {
            p = ref.get();
            if (p == null) {
                p = producerFactory.get();
                ref.set(p);
            }
            return p;
        }
    }

    public void close() {
        MqProducer p = ref.getAndSet(null);
        if (p != null) p.close();
    }
}
