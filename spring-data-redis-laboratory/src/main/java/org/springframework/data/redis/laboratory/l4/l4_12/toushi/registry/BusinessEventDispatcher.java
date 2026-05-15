package org.springframework.data.redis.laboratory.l4.l4_12.toushi.registry;

import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 派发器。对应 SDR 的 dispatchMessage / processMessage。
 *
 * <p>三个偷师点：
 * 1. 派发走 Executor，让 IO 线程不阻塞——SDR 也是这么干的。
 * 2. 每个 listener 调用包 try/catch，单个 listener 异常不影响其他——异常隔离。
 * 3. 把"匹配"和"派发"拆成两个职责（registry 找匹配、dispatcher 跑），单一职责更易扩展。
 */
public class BusinessEventDispatcher {

    private final BusinessListenerRegistry registry;
    private final Executor executor;

    public BusinessEventDispatcher(BusinessListenerRegistry registry, Executor executor) {
        this.registry = registry;
        this.executor = executor;
    }

    public void dispatch(String channel, String body) {
        List<BusinessListenerRegistry.Match> matches = registry.findMatches(channel);
        if (matches.isEmpty()) return;
        for (BusinessListenerRegistry.Match m : matches) {
            BusinessMessage msg = new BusinessMessage(channel, m.matchedPattern, body);
            executor.execute(() -> {
                try {
                    m.listener.onMessage(msg);
                } catch (Throwable t) {
                    LogPrinter.print("Dispatcher-ERR",
                            "channel=" + channel + " err=" + t.getClass().getSimpleName() +
                                    ":" + t.getMessage());
                }
            });
        }
    }
}
