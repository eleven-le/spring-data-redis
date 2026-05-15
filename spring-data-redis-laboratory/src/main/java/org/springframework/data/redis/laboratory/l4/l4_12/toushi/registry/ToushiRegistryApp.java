package org.springframework.data.redis.laboratory.l4.l4_12.toushi.registry;

import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Executors;

/**
 * 入口：演示 listener registry + dispatcher 的"一对多 + 多对一 + pattern + 异常隔离"。
 *
 * <p>预期日志：
 * 1. product.changed -> productListener 触发（精准订阅）
 * 2. config.city -> configPatternListener 触发，matchedPattern=config.*
 * 3. config.risk -> configPatternListener + riskListener 同时触发
 *    （riskListener 同时订阅了 channel(config.risk) 和 pattern(config.*)？这里只演精准+pattern 各一份）
 * 4. one listener 抛异常 -> 不影响兄弟 listener
 */
public class ToushiRegistryApp {

    public static void main(String[] args) throws Exception {
        BusinessListenerRegistry registry = new BusinessListenerRegistry();
        BusinessEventDispatcher dispatcher = new BusinessEventDispatcher(
                registry, Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r);
            t.setName("toushi-dispatch-" + t.getId());
            return t;
        }));

        BusinessListener productListener = msg ->
                LogPrinter.print("ProductListener", "channel=" + msg.channel + " body=" + msg.body);

        BusinessListener configPatternListener = msg ->
                LogPrinter.print("ConfigPatternListener",
                        "matched=" + msg.matchedPattern + " actual=" + msg.channel + " body=" + msg.body);

        BusinessListener riskListener = msg ->
                LogPrinter.print("RiskListener", "channel=" + msg.channel + " body=" + msg.body);

        BusinessListener boomListener = msg -> { throw new RuntimeException("boom"); };

        // 一个 listener 监听多个 topic
        registry.add(productListener, Arrays.asList(
                BusinessTopic.channel("product.changed"),
                BusinessTopic.channel("user.status.changed")));
        // 一个 topic 多个 listener
        registry.add(configPatternListener, Collections.singleton(BusinessTopic.pattern("config.*")));
        registry.add(riskListener, Collections.singleton(BusinessTopic.channel("config.risk")));
        registry.add(boomListener, Collections.singleton(BusinessTopic.channel("config.risk")));

        LogPrinter.print("App", "--- dispatch events ---");
        dispatcher.dispatch("product.changed", "{\"productId\":1001,\"v\":2}");
        dispatcher.dispatch("user.status.changed", "{\"userId\":777,\"status\":\"BANNED\"}");
        dispatcher.dispatch("config.city", "shanghai delivery=3km");
        dispatcher.dispatch("config.risk", "max_amount=99999");

        Thread.sleep(800);

        LogPrinter.print("App", "--- remove configPatternListener, dispatch config.delivery ---");
        registry.remove(configPatternListener);
        dispatcher.dispatch("config.delivery", "weatherFee=2"); // 应当无人接收
        Thread.sleep(300);

        // 优雅退出
        System.exit(0);
    }
}
