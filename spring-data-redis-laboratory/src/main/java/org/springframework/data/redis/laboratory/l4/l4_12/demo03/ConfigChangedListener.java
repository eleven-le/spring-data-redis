package org.springframework.data.redis.laboratory.l4.l4_12.demo03;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;

import java.nio.charset.StandardCharsets;

/**
 * 配置中心热更新 listener，订阅 PatternTopic("lab.l412.config.*")。
 *
 * <p>关键点：onMessage(Message, byte[] pattern) 的 pattern 参数：
 * - ChannelTopic 触发时 pattern 为 null
 * - PatternTopic 触发时 pattern 是匹配上的模式串本身
 * 这是区分两种订阅方式的最直接信号，源码里 LettuceMessageListener.message
 * 也是据此把回调拆成 onMessage / onPMessage 两条路径。
 */
public class ConfigChangedListener implements MessageListener {
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        String patternStr = pattern == null ? "<channel>" : new String(pattern, StandardCharsets.UTF_8);
        LogPrinter.print("Config",
                "matched-pattern=" + patternStr + " actual-channel=" + channel + " body=" + body);
    }
}
