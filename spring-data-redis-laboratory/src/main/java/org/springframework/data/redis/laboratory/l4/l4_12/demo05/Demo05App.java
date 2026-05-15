package org.springframework.data.redis.laboratory.l4.l4_12.demo05;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_12.common.JsonCodec;
import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;
import org.springframework.data.redis.laboratory.l4.l4_12.common.Topics;
import org.springframework.data.redis.laboratory.l4.l4_12.common.TraceIds;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.TimeUnit;

/**
 * 入口：演示"消息丢失 -> 版本对账兜底"。
 *
 * <p>剧本：
 * 1. 启动 Node-A 的容器，订阅 lab.l412.demo05.config.changed
 * 2. 主数据写入 city/risk/delivery 三条配置 -> 各发一次广播
 *    Node-A 应用收到 [apply]
 * 3. 此时 Node-B 还没启动（=离线状态），错过广播
 * 4. 现在启动 Node-B 容器，但只通过 Pub/Sub 是补不齐的——演示 B 缓存为空
 * 5. 触发一次 ReconciliationScanner.runOnce() -> B 按 version 拉到所有 entry
 * 6. 后续再发新广播，B 也能正常增量同步
 */
public class Demo05App {

    public static void main(String[] args) throws Exception {
        AnnotationConfigApplicationContext ctx =
                new AnnotationConfigApplicationContext(Demo05Config.class);
        ctx.registerShutdownHook();

        StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);
        RedisConnectionFactory factory = ctx.getBean(RedisConnectionFactory.class);
        ConfigStore store = ctx.getBean(ConfigStore.class);
        NodeLocalConfigCache cacheA = ctx.getBean("cacheNodeA", NodeLocalConfigCache.class);

        TimeUnit.MILLISECONDS.sleep(500);

        // ① 三条配置变更 + 广播；Node-A 收到，Node-B 不在线，错过
        LogPrinter.print("App", "--- broadcast while NodeB OFFLINE ---");
        publish(template, store, "config.city", "shanghai-rule-v1");
        publish(template, store, "config.risk", "max_amount=99999");
        publish(template, store, "config.delivery", "weatherFee=2");
        TimeUnit.MILLISECONDS.sleep(500);

        // ② Node-B 上线（new 一个独立容器，模拟它是另一个进程）
        LogPrinter.print("App", "--- NodeB starting now ---");
        NodeLocalConfigCache cacheB = new NodeLocalConfigCache("B");
        ConfigChangedListener listenerB = new ConfigChangedListener(cacheB, store);

        RedisMessageListenerContainer containerB = new RedisMessageListenerContainer();
        containerB.setConnectionFactory(factory);
        containerB.afterPropertiesSet();
        containerB.addMessageListener(listenerB, new ChannelTopic(Topics.DEMO05_CONFIG_CHANGED));
        containerB.start();
        TimeUnit.MILLISECONDS.sleep(300);

        // ③ 仅靠 Pub/Sub，Node-B 缓存依然空——这就是 Pub/Sub 不可靠的事实
        LogPrinter.print("App", "--- NodeB cache snapshot AFTER ONLY pub/sub recovery ---");
        cacheB.printSnapshot();

        // ④ 触发兜底扫描，按 lastAppliedVersion 增量补齐
        LogPrinter.print("App", "--- run reconciliation scanner on NodeB ---");
        new ReconciliationScanner(cacheB, store).runOnce();
        cacheB.printSnapshot();

        // ⑤ 再发一条新广播，验证 B 已经在线，可以同步
        LogPrinter.print("App", "--- broadcast again, both nodes should apply ---");
        publish(template, store, "config.delivery", "weatherFee=4");
        TimeUnit.MILLISECONDS.sleep(500);

        cacheA.printSnapshot();
        cacheB.printSnapshot();

        containerB.stop();
        containerB.destroy();
        ctx.close();
    }

    private static void publish(StringRedisTemplate template, ConfigStore store,
                                String key, String value) {
        ConfigStore.Entry e = store.put(key, value);
        ConfigChangedEvent event = new ConfigChangedEvent(
                e.key, e.version, System.currentTimeMillis(), TraceIds.next());
        template.convertAndSend(Topics.DEMO05_CONFIG_CHANGED, JsonCodec.toJson(event));
    }
}
