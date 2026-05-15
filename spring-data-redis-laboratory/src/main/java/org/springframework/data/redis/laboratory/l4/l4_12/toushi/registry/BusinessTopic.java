package org.springframework.data.redis.laboratory.l4.l4_12.toushi.registry;

import java.util.Objects;

/**
 * 业务 topic 抽象。对标 SDR 的 Topic / ChannelTopic / PatternTopic。
 *
 * <p>偷师要点：SDR 用类型区分订阅语义（ChannelTopic 精准、PatternTopic 模式），
 * 这里简化为一个枚举字段，本质相同——把"语义差异"显式化、不要让消费方猜。
 */
public final class BusinessTopic {

    public enum Type { CHANNEL, PATTERN }

    private final String expression;
    private final Type type;

    private BusinessTopic(String expression, Type type) {
        this.expression = expression;
        this.type = type;
    }

    public static BusinessTopic channel(String name) { return new BusinessTopic(name, Type.CHANNEL); }
    public static BusinessTopic pattern(String pattern) { return new BusinessTopic(pattern, Type.PATTERN); }

    public String getExpression() { return expression; }
    public Type getType() { return type; }

    /** 极简 glob 匹配：仅支持 '*'，因为 Redis Pub/Sub 的 PSUBSCRIBE 大部分用法也只到这里。 */
    public boolean matches(String channel) {
        if (type == Type.CHANNEL) return expression.equals(channel);
        // pattern: foo.* / foo.*.bar / *
        String regex = expression.replace(".", "\\.").replace("*", ".*");
        return channel.matches(regex);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BusinessTopic that)) return false;
        return type == that.type && Objects.equals(expression, that.expression);
    }

    @Override
    public int hashCode() { return Objects.hash(expression, type); }

    @Override
    public String toString() { return type + "(" + expression + ")"; }
}
