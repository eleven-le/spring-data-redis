package org.springframework.data.redis.springboot.laboratory.l7_10;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lab.l7-10")
public class L710FlashSaleProperties {

    private int tokenTtlSeconds = 300;

    private int purchaseRecordTtlSeconds = 3600;

    private int orderWorkerDelayMs = 80;

    public int getTokenTtlSeconds() {
        return this.tokenTtlSeconds;
    }

    public void setTokenTtlSeconds(int tokenTtlSeconds) {
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    public int getPurchaseRecordTtlSeconds() {
        return this.purchaseRecordTtlSeconds;
    }

    public void setPurchaseRecordTtlSeconds(int purchaseRecordTtlSeconds) {
        this.purchaseRecordTtlSeconds = purchaseRecordTtlSeconds;
    }

    public int getOrderWorkerDelayMs() {
        return this.orderWorkerDelayMs;
    }

    public void setOrderWorkerDelayMs(int orderWorkerDelayMs) {
        this.orderWorkerDelayMs = orderWorkerDelayMs;
    }
}
