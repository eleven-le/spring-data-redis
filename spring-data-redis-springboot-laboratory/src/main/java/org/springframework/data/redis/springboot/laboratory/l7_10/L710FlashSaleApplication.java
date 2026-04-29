package org.springframework.data.redis.springboot.laboratory.l7_10;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(L710FlashSaleProperties.class)
public class L710FlashSaleApplication {

    public static void main(String[] args) {
        SpringApplication.run(L710FlashSaleApplication.class, args);
    }
}
