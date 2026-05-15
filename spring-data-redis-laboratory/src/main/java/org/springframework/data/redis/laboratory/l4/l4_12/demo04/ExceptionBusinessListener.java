package org.springframework.data.redis.laboratory.l4.l4_12.demo04;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

/**
 * 模拟"必然抛异常"的 listener。
 *
 * <p>关键观察：异常会被容器捕获并转给 ErrorHandler。
 * 源码路径见 {@code RedisMessageListenerContainer#processMessage}：
 * try { listener.onMessage(...) } catch (Throwable t) { handleListenerException(t); }
 *
 * <p>没配 ErrorHandler 时容器只会用默认 logger 打 warn——线上排障极容易遗漏。
 */
public class ExceptionBusinessListener implements MessageListener {
    @Override
    public void onMessage(Message message, byte[] pattern) {
        throw new IllegalStateException("simulated downstream failure: " +
                new String(message.getBody()));
    }
}
