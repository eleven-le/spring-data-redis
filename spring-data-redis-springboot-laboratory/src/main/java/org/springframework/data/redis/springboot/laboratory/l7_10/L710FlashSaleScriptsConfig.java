package org.springframework.data.redis.springboot.laboratory.l7_10;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class L710FlashSaleScriptsConfig {

    @Bean
    public DefaultRedisScript<Long> l710TokenGateScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/l7_10_token_gate.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public DefaultRedisScript<Long> l710PurchaseScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/l7_10_purchase.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
