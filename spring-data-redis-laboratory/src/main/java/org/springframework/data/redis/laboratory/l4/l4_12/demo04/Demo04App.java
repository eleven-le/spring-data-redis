package org.springframework.data.redis.laboratory.l4.l4_12.demo04;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;
import org.springframework.data.redis.laboratory.l4.l4_12.common.Topics;

import java.util.concurrent.TimeUnit;

/**
 * 入口：观察线程隔离与 ErrorHandler 行为。
 *
 * <p>预期日志特征：
 * - "Slow begin/end"打印的线程名都带 biz-pool- 前缀（最多 4 个并发，因为 corePoolSize=4）
 * - 同时连续发 6 条 slow 消息，能看到队列累积、并发只有 4 条在跑
 * - "ErrorHandler listener crashed: IllegalStateException"被全局回调统一接住
 * - 全程订阅线程不会被慢业务阻塞——可以再连续发更多消息观察
 *
 * <p>生产建议（写在脑子里别照抄注释）：
 * listener 只做：解析消息 + 校验 traceId + 投递到下游业务线程池 + 必要回查。
 * 不在 listener 里同步执行重业务。
 */
public class Demo04App {

    public static void main(String[] args) throws Exception {
        AnnotationConfigApplicationContext ctx =
                new AnnotationConfigApplicationContext(Demo04Config.class);
        ctx.registerShutdownHook();

        StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);
        TimeUnit.MILLISECONDS.sleep(500);

        LogPrinter.print("App", "--- burst 6 slow messages, watch biz-pool- thread reuse ---");
        for (int i = 0; i < 6; i++) {
            template.convertAndSend(Topics.DEMO04_SLOW, "task-" + i);
        }
        TimeUnit.SECONDS.sleep(3);

        LogPrinter.print("App", "--- send 3 messages that always throw, watch ErrorHandler ---");
        for (int i = 0; i < 3; i++) {
            template.convertAndSend(Topics.DEMO04_BOOM, "boom-" + i);
        }
        TimeUnit.SECONDS.sleep(1);

        ctx.close();
    }
}
