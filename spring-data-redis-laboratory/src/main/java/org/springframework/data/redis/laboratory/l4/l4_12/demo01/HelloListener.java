package org.springframework.data.redis.laboratory.l4.l4_12.demo01;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;

import java.nio.charset.StandardCharsets;

/**
 * 最小可运行 listener：只打印线程、channel、body。
 *
 * <p>断点观察点：
 * - 在 onMessage 上打断点，观察栈帧链：
 *   LettuceMessageListener.message
 *   -> RedisMessageListenerContainer$DispatchMessageListener.onMessage
 *   -> RedisMessageListenerContainer.dispatchMessage
 *   -> taskExecutor.execute(() -> processMessage(...))
 *   -> processMessage(listener, message, source)
 *   -> 最终回到这里 onMessage
 * - 这条链就是 RedisMessageListenerContainer 设计的精华：
 *   接收线程 ≠ 业务执行线程。
 */
public class HelloListener implements MessageListener {

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        String patternStr = pattern == null ? "<null>" : new String(pattern, StandardCharsets.UTF_8);

        LogPrinter.print("Demo01-Listener",
                "channel=" + channel + " pattern=" + patternStr + " body=" + body);
    }
}
