package org.springframework.data.redis.springboot.laboratory.l7_10;

final class L710FlashSaleKeys {

    private static final String PREFIX = "lab:l7:10:flash-sale:";

    private L710FlashSaleKeys() {
    }

    static String roundKey(long skuId) {
        return PREFIX + "{" + skuId + "}:round";
    }

    static String configKey(long skuId) {
        return PREFIX + "{" + skuId + "}:config";
    }

    static String stockKey(long skuId, long round) {
        return PREFIX + "{" + skuId + "}:r:" + round + ":stock";
    }

    static String tokenKey(long skuId, long round, String userId) {
        return PREFIX + "{" + skuId + "}:r:" + round + ":token:" + userId;
    }

    static String orderKey(long skuId, long round, String userId) {
        return PREFIX + "{" + skuId + "}:r:" + round + ":order:" + userId;
    }

    static String rateLimitKey(long skuId, long round) {
        return PREFIX + "{" + skuId + "}:r:" + round + ":gate:token";
    }
}
