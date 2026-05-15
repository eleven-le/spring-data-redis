package org.springframework.data.redis.laboratory.l4.l4_12.demo01;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;
import org.springframework.data.redis.laboratory.l4.l4_12.common.Topics;

import java.util.concurrent.TimeUnit;

/**
 * 入口：
 * mvn -pl spring-data-redis-laboratory exec:java \
 * -Dexec.mainClass=org.springframework.data.redis.laboratory.l4.l4_12.demo01.Demo01App
 * <p>
 * 预期日志：
 * - 启动后大约几百毫秒，控制台首次打印 listener 收到的 Hello world #N
 * - 线程名形如 "RedisMessageListenerContainer-1"——这是默认 SimpleAsyncTaskExecutor 起的名
 * - channel = lab.l412.demo01.hello，pattern = <null>（因为我们用 ChannelTopic 不是 PatternTopic）
 * <p>
 * 推荐断点：
 * 1. RedisMessageListenerContainer#afterPropertiesSet（看 manageExecutor=true 路径）
 * 2. RedisMessageListenerContainer#start（看 SmartLifecycle 自动触发）
 * 3. RedisMessageListenerContainer#lazyListen
 * 4. RedisMessageListenerContainer#processMessage（dispatchMessage 之后异步触发）
 */
public class Demo01App {

    public static void main(String[] args) throws Exception {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Demo01Config.class);
        // 注册 JVM 钩子，关闭时让 SmartLifecycle 走完 stop+destroy 链路
        ctx.registerShutdownHook();

        StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);

        // 给容器一点时间完成 lazyListen → SUBSCRIBE 命令真正发到 Redis
        // 实际生产环境里你不应该 sleep，应改用 setMaxSubscriptionRegistrationWaitingTime
        TimeUnit.MILLISECONDS.sleep(500);

        for (int i = 0; i < 3; i++) {
            String body = "Hello world #" + i;
            LogPrinter.print("Demo01-Publisher", "publishing -> " + body);
            // RedisTemplate.convertAndSend 内部最终调 RedisConnection.publish(channel, body)
            template.convertAndSend(Topics.DEMO01_HELLO, body);
            TimeUnit.MILLISECONDS.sleep(200);
        }

        // 等 listener 异步处理完
        TimeUnit.SECONDS.sleep(1);
        ctx.close();
    }
}
