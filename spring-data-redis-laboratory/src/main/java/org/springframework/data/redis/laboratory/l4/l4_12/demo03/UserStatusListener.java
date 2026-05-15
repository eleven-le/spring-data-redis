package org.springframework.data.redis.laboratory.l4.l4_12.demo03;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;

import java.nio.charset.StandardCharsets;

/**
 * 用户状态变更（封禁、会员等级、黑名单）listener。
 * 真实业务里会驱动：本地白/黑名单缓存刷新、踢下线、清 session。
 */
public class UserStatusListener implements MessageListener {
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        LogPrinter.print("UserStatus", "channel=" + channel + " body=" + body);
    }
}
