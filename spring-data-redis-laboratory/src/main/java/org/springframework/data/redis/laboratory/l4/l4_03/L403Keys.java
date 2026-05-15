package org.springframework.data.redis.laboratory.l4.l4_03;

/**
 * L4-03 章节内统一使用的 Redis key 前缀，避免污染共享 Redis。
 */
public final class L403Keys {

    public static final String PREFIX = "l4:03:";

    public static final String STRING_LAB = PREFIX + "string:lab:";
    public static final String SMS_CODE   = PREFIX + "sms:code:";
    public static final String SMS_LIMIT  = PREFIX + "sms:limit:";
    public static final String TOKEN      = PREFIX + "token:";
    public static final String USER_TOKEN = PREFIX + "user:token:";
    public static final String COUNTER    = PREFIX + "counter:";
    public static final String IDEMPOTENT = PREFIX + "idempotent:";

    public static final String HASH_LAB     = PREFIX + "hash:lab:";
    public static final String USER_PROFILE = PREFIX + "user:profile:";
    public static final String CART         = PREFIX + "cart:";
    public static final String SKU_STOCK    = PREFIX + "sku:stock:";
    public static final String USER_BEHAV   = PREFIX + "user:behavior:";

    private L403Keys() {}
}
