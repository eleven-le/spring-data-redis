package org.springframework.data.redis.laboratory.l4.l4_12.toushi.lifecycle;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;

import java.util.concurrent.TimeUnit;

/**
 * 入口：演示偷师容器的完整生命周期。
 *
 * <p>预期日志：
 * 1. ApplicationContext 启动 -> afterPropertiesSet 执行（initialized=true）
 * 2. SmartLifecycle 自动 start -> pollLoop 启动
 * 3. publish() 三条消息 -> 三个 listener 在 dispatch 线程池里被分别调用
 * 4. RiskListener 抛出异常 -> 不影响 CityConfigListener
 * 5. context.close() -> SmartLifecycle.stop() -> DisposableBean.destroy()
 */
public class ToushiLifecycleApp {

    @Configuration
    static class Cfg {
        @Bean(initMethod = "afterPropertiesSet", destroyMethod = "destroy")
        public BusinessEventListenerContainer businessContainer() {
            return new BusinessEventListenerContainer();
        }
    }

    public static void main(String[] args) throws Exception {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Cfg.class);
        ctx.registerShutdownHook();

        BusinessEventListenerContainer container =
                ctx.getBean(BusinessEventListenerContainer.class);

        // 注册 listeners：模拟风控规则 + 城市配置两个业务方
        container.addListener("risk.rule.changed", e ->
                LogPrinter.print("RiskListener", "rule -> " + e.body));
        container.addListener("city.config.changed", e ->
                LogPrinter.print("CityListener", "city -> " + e.body));
        // 故意抛错的 listener，验证异常隔离
        container.addListener("risk.rule.changed", e -> {
            throw new RuntimeException("downstream rpc timeout");
        });

        TimeUnit.MILLISECONDS.sleep(200);
        LogPrinter.print("App", "--- publish events ---");
        container.publish(new BusinessEvent("risk.rule.changed", "max_amount=99999"));
        container.publish(new BusinessEvent("city.config.changed", "shanghai delivery range=3km"));
        container.publish(new BusinessEvent("risk.rule.changed", "blacklist+1"));
        TimeUnit.MILLISECONDS.sleep(800);

        ctx.close();
    }
}
