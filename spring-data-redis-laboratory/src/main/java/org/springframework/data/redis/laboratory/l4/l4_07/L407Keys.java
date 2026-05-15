package org.springframework.data.redis.laboratory.l4.l4_07;

/**
 * L4-07 章节统一 key 前缀。
 * <p>
 * 命名规范全部小写、冒号分段、业务语义前置：
 * <pre>
 *   l4:07:home:{moduleType}:{id}            首页聚合缓存
 *   l4:07:product:detail:{productId}        商品详情缓存
 *   l4:07:counter:{counterType}:{id}        行为计数器
 *   l4:07:rank:{rankType}:{period}          榜单
 *   l4:07:delete:demo:{idx}                 批量删除示例
 *   l4:07:mixed:user:{userId}:nick          混合命令场景：用户昵称
 *   l4:07:mixed:user:{userId}:profile       混合命令场景：用户资料 hash
 *   l4:07:mixed:rank:product:hot            混合命令场景：商品热榜
 *   l4:07:mixed:activity:{actId}:join       混合命令场景：活动参与人 set
 *   l4:07:mixed:api:{api}:visit             混合命令场景：接口访问次数
 *   l4:07:pipeline:lab:{idx}                pipeline 基础实验 key
 * </pre>
 * <p>
 * 注意：
 * 1) 统一前缀方便 redis-cli SCAN 排查实验残留；
 * 2) 不要为 demo 引入魔法字符（空格、换行、反斜杠）。
 */
public final class L407Keys {

    public static final String PREFIX = "l4:07:";

    private L407Keys() {
    }

    public static String labKey(int idx) {
        return PREFIX + "pipeline:lab:" + idx;
    }

    public static String homeModule(String moduleType, String id) {
        return PREFIX + "home:" + moduleType + ":" + id;
    }

    public static String productDetail(long productId) {
        return PREFIX + "product:detail:" + productId;
    }

    public static String counter(String counterType, String id) {
        return PREFIX + "counter:" + counterType + ":" + id;
    }

    public static String rank(String rankType, String period) {
        return PREFIX + "rank:" + rankType + ":" + period;
    }

    public static String deleteDemo(int idx) {
        return PREFIX + "delete:demo:" + idx;
    }

    public static String mixedUserNick(String userId) {
        return PREFIX + "mixed:user:" + userId + ":nick";
    }

    public static String mixedUserProfile(String userId) {
        return PREFIX + "mixed:user:" + userId + ":profile";
    }

    public static String mixedHotProductRank() {
        return PREFIX + "mixed:rank:product:hot";
    }

    public static String mixedActivityJoinSet(String activityId) {
        return PREFIX + "mixed:activity:" + activityId + ":join";
    }

    public static String mixedApiVisit(String api) {
        return PREFIX + "mixed:api:" + api + ":visit";
    }
}
