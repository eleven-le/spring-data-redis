package org.springframework.data.redis.laboratory.l4.l4_10;

/**
 * L4-10 章节统一 key 前缀（生产级版,所有 Cluster 多 key 场景全部 hash tag）。
 * <p>
 * 真实生产里 Lua 脚本经常一次摸 3-5 个 key,只要这些 key 不在同 slot,Redis Cluster 直接 CROSSSLOT 拒掉。
 * 所以本章所有可能跨 key 的场景都把"业务唯一键"用 {tag} 锚住。
 * <pre>
 *   l4:10:script:lab:{idx}                                基础脚本实验
 *
 *   秒杀:
 *   l4:10:stock:sku:{skuId}                               库存(主)
 *   l4:10:user_purchased:{skuId}                          hash field=userId 值=已购数(用户级限购)
 *   l4:10:stock:tx:{skuId}                                LIST 扣减流水(LPUSH+LTRIM)
 *   l4:10:idem:stock:{skuId}:{requestId}                  幂等键(value=上次结果 JSON)
 *   l4:10:txid:{skuId}                                    全局自增 txId 序列
 *
 *   优惠券:
 *   l4:10:coupon:{activityId}:stock                       券库存
 *   l4:10:coupon:{activityId}:users                       领取用户集合(去重)
 *   l4:10:coupon:{activityId}:claim:{userId}              领取详情 hash(claimId/couponCode/...)
 *   l4:10:coupon:{activityId}:seq                         领取顺序号(生成 claimId)
 *   l4:10:coupon:{activityId}:rank                        领取榜 zset(score=领取时间)
 *
 *   幂等门:
 *   l4:10:idem:{biz}:{requestId}                          通用幂等门(value=业务结果 JSON)
 *
 *   分布式锁:
 *   l4:10:lock:{biz}:{resourceId}                         锁主键(value=token)
 *   l4:10:lock:{biz}:{resourceId}:meta                    持有元数据 hash(acquiredAt 等)
 *
 *   滑动窗口:
 *   l4:10:rate:{userId}:{api}                             zset
 *
 *   延迟队列:
 *   l4:10:delay:{biz}:ready                               待触发 zset
 *   l4:10:delay:{biz}:detail                              任务详情 hash
 *   l4:10:delay:{biz}:attempts                            尝试次数 hash
 *   l4:10:delay:{biz}:inflight                            正在处理中 zset(visibility timeout)
 *   l4:10:delay:{biz}:dead                                死信 zset
 *
 *   计数器:
 *   l4:10:counter:{userId}:{action}
 * </pre>
 */
public final class L410Keys {

    public static final String PREFIX = "l4:10:";

    private L410Keys() {
    }

    public static String labKey(int idx) {
        return PREFIX + "script:lab:" + idx;
    }

    // ========= 秒杀 =========
    public static String stock(String skuId) {
        return PREFIX + "stock:sku:{" + skuId + "}";
    }

    public static String userPurchased(String skuId) {
        return PREFIX + "user_purchased:{" + skuId + "}";
    }

    public static String stockTx(String skuId) {
        return PREFIX + "stock:tx:{" + skuId + "}";
    }

    public static String stockIdem(String skuId, String requestId) {
        return PREFIX + "idem:stock:{" + skuId + "}:" + requestId;
    }

    public static String stockTxId(String skuId) {
        return PREFIX + "txid:{" + skuId + "}";
    }

    // ========= 优惠券 =========
    public static String couponStock(String activityId) {
        return PREFIX + "coupon:{" + activityId + "}:stock";
    }

    public static String couponUsers(String activityId) {
        return PREFIX + "coupon:{" + activityId + "}:users";
    }

    public static String couponClaim(String activityId, String userId) {
        return PREFIX + "coupon:{" + activityId + "}:claim:" + userId;
    }

    public static String couponSeq(String activityId) {
        return PREFIX + "coupon:{" + activityId + "}:seq";
    }

    public static String couponRank(String activityId) {
        return PREFIX + "coupon:{" + activityId + "}:rank";
    }

    // ========= 幂等门 =========
    public static String idem(String biz, String requestId) {
        return PREFIX + "idem:{" + biz + "}:" + requestId;
    }

    // ========= 分布式锁 =========
    public static String lock(String biz, String resourceId) {
        return PREFIX + "lock:{" + biz + "}:" + resourceId;
    }

    public static String lockMeta(String biz, String resourceId) {
        return PREFIX + "lock:{" + biz + "}:" + resourceId + ":meta";
    }

    // ========= 限流 =========
    public static String rate(String userId, String api) {
        return PREFIX + "rate:{" + userId + "}:" + api;
    }

    // ========= 延迟队列 =========
    public static String delayReady(String biz) {
        return PREFIX + "delay:{" + biz + "}:ready";
    }

    public static String delayDetail(String biz) {
        return PREFIX + "delay:{" + biz + "}:detail";
    }

    public static String delayAttempts(String biz) {
        return PREFIX + "delay:{" + biz + "}:attempts";
    }

    public static String delayInflight(String biz) {
        return PREFIX + "delay:{" + biz + "}:inflight";
    }

    public static String delayDead(String biz) {
        return PREFIX + "delay:{" + biz + "}:dead";
    }

    // ========= 计数器 =========
    public static String counter(String userId, String action) {
        return PREFIX + "counter:{" + userId + "}:" + action;
    }
}
