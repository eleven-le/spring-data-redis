package org.springframework.data.redis.laboratory.l4.l4_12.demo03;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_12.common.JsonCodec;
import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;
import org.springframework.data.redis.laboratory.l4.l4_12.common.Topics;
import org.springframework.data.redis.laboratory.l4.l4_12.common.TraceIds;
import org.springframework.data.redis.laboratory.l4.l4_12.demo02.ProductChangedEvent;
import org.springframework.data.redis.laboratory.l4.l4_12.demo02.ProductRepository;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.TimeUnit;

/**
 * 入口：演示 PatternTopic 匹配 + 动态 remove。
 *
 * <p>预期日志：
 * 1. 发布 lab.l412.config.city -> ConfigListener 收到，pattern=lab.l412.config.*
 * 2. 发布 lab.l412.config.risk / delivery -> 都被同一 PatternTopic 命中
 * 3. 发布 lab.l412.product.changed -> ProductListener 触发
 * 4. 调用 removeMessageListener 移除 ConfigListener 的 PatternTopic 订阅
 * 5. 再次发 config.city -> ConfigListener 不再触发（注册表中已删）
 * 6. 但 product.changed 依然触发，证明只是局部 unsubscribe，不是整个容器停摆
 */
public class Demo03App {

    public static void main(String[] args) throws Exception {
        AnnotationConfigApplicationContext ctx =
                new AnnotationConfigApplicationContext(Demo03Config.class);
        ctx.registerShutdownHook();

        StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);
        RedisMessageListenerContainer container = ctx.getBean(RedisMessageListenerContainer.class);
        ConfigChangedListener configListener = ctx.getBean(ConfigChangedListener.class);
        ProductRepository repo = ctx.getBean(ProductRepository.class);

        TimeUnit.MILLISECONDS.sleep(500);

        // ① PatternTopic 匹配三种 config 子频道
        LogPrinter.print("App", "--- pattern match ---");
        template.convertAndSend(Topics.CONFIG_CITY, "{\"city\":\"shanghai\",\"deliveryRange\":3000}");
        template.convertAndSend(Topics.CONFIG_RISK, "{\"rule\":\"max_amount\",\"value\":99999}");
        template.convertAndSend(Topics.CONFIG_DELIVERY, "{\"weatherFee\":2}");
        TimeUnit.MILLISECONDS.sleep(400);

        // ② Channel 精准订阅
        LogPrinter.print("App", "--- channel topic ---");
        long v = repo.updatePrice(1001L, 3000);
        template.convertAndSend(Topics.PRODUCT_CHANGED,
                JsonCodec.toJson(new ProductChangedEvent(1001L, 88L,
                        ProductChangedEvent.ChangeType.PRICE, v, System.currentTimeMillis(), TraceIds.next())));
        template.convertAndSend(Topics.USER_STATUS_CHANGED, "{\"userId\":777,\"status\":\"BANNED\"}");
        TimeUnit.MILLISECONDS.sleep(400);

        // ③ 动态移除 PatternTopic 订阅
        LogPrinter.print("App", "--- dynamic remove pattern listener ---");
        container.removeMessageListener(configListener, new PatternTopic(Topics.CONFIG_PATTERN));
        TimeUnit.MILLISECONDS.sleep(200);

        // ④ 验证：config.* 不再被 ConfigListener 接收，但 product / user 不受影响
        template.convertAndSend(Topics.CONFIG_CITY, "{\"after-remove\":true}");
        template.convertAndSend(Topics.USER_STATUS_CHANGED, "{\"userId\":888,\"status\":\"VIP\"}");
        TimeUnit.MILLISECONDS.sleep(500);

        ctx.close();
    }
}
