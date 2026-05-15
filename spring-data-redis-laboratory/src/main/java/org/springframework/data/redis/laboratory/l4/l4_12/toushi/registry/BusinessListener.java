package org.springframework.data.redis.laboratory.l4.l4_12.toushi.registry;

@FunctionalInterface
public interface BusinessListener {
    void onMessage(BusinessMessage message);
}
