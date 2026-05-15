package org.springframework.data.redis.laboratory.l4.l4_12.toushi.registry;

/**
 * 业务消息——明确把 channel（实际频道）和 matchedPattern（命中的模式串）分开，
 * 复刻 SDR Message 的语义。
 */
public class BusinessMessage {
    public final String channel;
    public final String matchedPattern;
    public final String body;

    public BusinessMessage(String channel, String matchedPattern, String body) {
        this.channel = channel;
        this.matchedPattern = matchedPattern;
        this.body = body;
    }
}
