package org.springframework.data.redis.laboratory.l4.l4_04;

/**
 * L4-04 章节统一 key 前缀，避免污染共享 Redis。
 */
public final class L404Keys {

    public static final String PREFIX = "l4:04:";

    public static final String LIST_LAB     = PREFIX + "list:lab:";
    public static final String ORDER_QUEUE  = PREFIX + "queue:order:fulfillment";
    public static final String RECENT_VIEW  = PREFIX + "list:recent:view:";
    public static final String FEED         = PREFIX + "list:feed:";

    public static final String SET_LAB      = PREFIX + "set:lab:";
    public static final String CHECKIN      = PREFIX + "set:checkin:";
    public static final String LOTTERY_POOL = PREFIX + "set:lottery:pool:";
    public static final String LIKE_USERS   = PREFIX + "set:like:item:";
    public static final String TAG_USERS    = PREFIX + "set:tag:users:";

    public static final String ZSET_LAB     = PREFIX + "zset:lab:";
    public static final String SALES_RANK   = PREFIX + "zset:rank:sales:";
    public static final String HOT_CONTENT  = PREFIX + "zset:rank:hot:";
    public static final String DELAY_QUEUE  = PREFIX + "zset:delay:order:close";
    public static final String PRIORITY_Q   = PREFIX + "zset:priority:task";

    private L404Keys() {}
}
