package org.springframework.data.redis.laboratory.l5.l5_02;

/**
 * L5-02 章节实验统一使用的 Redis key 前缀，避免污染共享 Redis。
 * <p>
 * 全部 key 都走 StringRedisSerializer，redis-cli 一眼可读：
 * <pre>
 *   KEYS l5:02:*
 *   GET  l5:02:token:user:u-1001
 *   GET  l5:02:profile:user:u-1001
 *   HGETALL l5:02:cart:correct:u-1001
 * </pre>
 * 实验结束清理：
 * <pre>
 *   redis-cli --scan --pattern 'l5:02:*' | xargs -n 50 redis-cli del
 * </pre>
 */
public final class L502Keys {

    public static final String PREFIX = "l5:02:";

    /** 登录 Token——简单字符串，走 String 序列化。 */
    public static final String TOKEN_USER = PREFIX + "token:user:";

    /** 用户画像——复杂对象，走 Generic JSON。 */
    public static final String PROFILE_USER = PREFIX + "profile:user:";

    /** 商品详情快照——稳定 DTO，走 Typed JSON（跨服务共享场景）。 */
    public static final String PRODUCT_DETAIL = PREFIX + "product:detail:";

    /** 订单快照（JDK）——专门用来对比 JDK 序列化的字节形态和兼容陷阱。 */
    public static final String ORDER_SNAPSHOT_JDK = PREFIX + "order:snapshot:jdk:";

    /** 风控标签——byte[] 透传场景。 */
    public static final String RISK_TAG = PREFIX + "risk:tag:";

    /** 首页营销卡片——多态对象，演示 Generic 的 @class 还原能力。 */
    public static final String HOME_CARDS = PREFIX + "home:cards:";

    /** 购物车（正确策略）。 */
    public static final String CART_CORRECT = PREFIX + "cart:correct:";

    /** 购物车（错误 hashKey 策略）——演示 HGET 伪丢数据。 */
    public static final String CART_WRONG = PREFIX + "cart:wrong:";

    /** 断点调试通道。 */
    public static final String DEBUG = PREFIX + "debug:";

    private L502Keys() {}
}
