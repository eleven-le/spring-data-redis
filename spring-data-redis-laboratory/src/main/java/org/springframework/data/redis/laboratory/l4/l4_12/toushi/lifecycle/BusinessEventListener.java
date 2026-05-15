package org.springframework.data.redis.laboratory.l4.l4_12.toushi.lifecycle;

/** 业务级 listener 接口，对标 Spring Data Redis 的 MessageListener。 */
public interface BusinessEventListener {
    void onEvent(BusinessEvent event);
}
