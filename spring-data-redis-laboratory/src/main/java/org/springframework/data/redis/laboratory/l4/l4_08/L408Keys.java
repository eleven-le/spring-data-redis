package org.springframework.data.redis.laboratory.l4.l4_08;

/**
 * L4-08 章节统一 key 前缀。
 * <p>
 * 命名规范：全部小写、冒号分段、业务语义前置。
 * <pre>
 *   l4:08:tx:lab:{idx}                       事务基础实验
 *   l4:08:tx:rmap:{idx}                      mixed results 映射实验
 *   l4:08:watch:lab:{idx}                    WATCH 基础实验
 *   l4:08:points:user:{userId}               会员积分余额（演示用，事实源仍是 DB）
 *   l4:08:exchange:order:{orderId}           兑换订单 hash
 *   l4:08:stock:sku:{skuId}                  商品库存（事务版本演示）
 *   l4:08:coupon:{actId}:stock               活动券库存
 *   l4:08:coupon:{actId}:users               活动券已领集合
 *   l4:08:task:user:{userId}:{taskId}        用户任务状态
 *   l4:08:rank:task:{date}                   每日任务排行榜
 *   l4:08:balance:user:{userId}              账户余额（反例 key，仅演示）
 *   l4:08:conflict:demo:{idx}                WATCH 冲突复现 key
 * </pre>
 * <p>
 * Cluster hash tag 友好：优惠券两个 key 用 {actId} 包裹，使其落在同一 slot。
 */
public final class L408Keys {

    public static final String PREFIX = "l4:08:";

    private L408Keys() {
    }

    public static String txLab(int idx) {
        return PREFIX + "tx:lab:" + idx;
    }

    public static String txResultMap(int idx) {
        return PREFIX + "tx:rmap:" + idx;
    }

    public static String watchLab(int idx) {
        return PREFIX + "watch:lab:" + idx;
    }

    public static String pointsUser(String userId) {
        return PREFIX + "points:user:" + userId;
    }

    public static String exchangeOrder(String orderId) {
        return PREFIX + "exchange:order:" + orderId;
    }

    public static String stockSku(String skuId) {
        return PREFIX + "stock:sku:" + skuId;
    }

    /** Cluster hash tag：{actId} 让 stock / users 同 slot。 */
    public static String couponStock(String actId) {
        return PREFIX + "coupon:{" + actId + "}:stock";
    }

    public static String couponUsers(String actId) {
        return PREFIX + "coupon:{" + actId + "}:users";
    }

    public static String taskStatus(String userId, String taskId) {
        return PREFIX + "task:user:" + userId + ":" + taskId;
    }

    public static String taskRank(String date) {
        return PREFIX + "rank:task:" + date;
    }

    public static String balanceUser(String userId) {
        return PREFIX + "balance:user:" + userId;
    }

    public static String conflictDemo(int idx) {
        return PREFIX + "conflict:demo:" + idx;
    }
}
