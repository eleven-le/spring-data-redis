package org.springframework.data.redis.laboratory.l4.l4_12.toushi.lifecycle;

/**
 * 业务事件载体。模拟 Pub/Sub 的 Message 概念，但不涉及 Redis。
 */
public class BusinessEvent {
    public final String topic;
    public final String body;

    public BusinessEvent(String topic, String body) {
        this.topic = topic;
        this.body = body;
    }
}
