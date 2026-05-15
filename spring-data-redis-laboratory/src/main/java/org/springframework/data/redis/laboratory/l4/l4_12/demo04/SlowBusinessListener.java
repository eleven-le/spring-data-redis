package org.springframework.data.redis.laboratory.l4.l4_12.demo04;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 模拟"慢 listener"。
 *
 * <p>真实生产中慢的来源往往是：
 * <ul>
 *   <li>同步调 DB（尤其涉及分库分表的复杂查询）</li>
 *   <li>同步 RPC 拉商品全量画像 / 风控特征</li>
 *   <li>大对象反序列化或 Bean 拷贝</li>
 *   <li>批量回查并刷新本地多级缓存</li>
 *   <li>同步落地审计日志（写盘 / 写 ES）</li>
 * </ul>
 *
 * <p>风险：如果用容器默认的 SimpleAsyncTaskExecutor，每来一条消息就 new 线程，
 * 慢 listener 累积到一定程度会把 JVM 线程数顶爆——这是默认配置的最大隐患。
 * 自定义 ThreadPoolTaskExecutor + 有界队列才是生产姿势。
 */
public class SlowBusinessListener implements MessageListener {

    private final long workMillis;

    public SlowBusinessListener(long workMillis) {
        this.workMillis = workMillis;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        LogPrinter.print("Slow", "begin body=" + body);
        try {
            TimeUnit.MILLISECONDS.sleep(workMillis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        LogPrinter.print("Slow", "end   body=" + body);
    }
}
