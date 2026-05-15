package org.springframework.data.redis.laboratory.l4.l4_10.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.laboratory.l4.l4_10.L410Keys;
import org.springframework.data.redis.laboratory.l4.l4_10.result.CouponClaimResult;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 生产级·优惠券领取(去重 + 扣库存 + 核销码生成 + 排行榜 + 有效期)。
 * <p>
 * 一次脚本调用产出 4 项工作:
 * <ol>
 *   <li>SISMEMBER 防重领;重领时把上次的 claimId/couponCode 回传(用户重新进入活动页能看到自己的券)</li>
 *   <li>DECR 库存</li>
 *   <li>HSET 领取详情 + PEXPIREAT 与券有效期一致</li>
 *   <li>ZADD 排行榜,前 N 名打勋章</li>
 * </ol>
 * 所有 KEYS 用 {activityId} 锚同 slot,Cluster 不会 CROSSSLOT。
 */
public class L410CouponClaimLuaScenario {

    private final StringRedisTemplate template;
    private final RedisScript<String> script;
    private final ObjectMapper mapper;

    public L410CouponClaimLuaScenario(StringRedisTemplate template,
                                      RedisScript<String> couponClaimScript,
                                      ObjectMapper mapper) {
        this.template = template;
        this.script = couponClaimScript;
        this.mapper = mapper;
    }

    public void initCouponStock(String activityId, long stock) {
        template.opsForValue().set(L410Keys.couponStock(activityId), String.valueOf(stock));
    }

    public CouponClaimResult claim(String activityId, String userId,
                                   String couponCodePrefix, long activityEndMs,
                                   long couponValidMs, long topN) {
        long now = System.currentTimeMillis();
        List<String> keys = Arrays.asList(
                L410Keys.couponStock(activityId),
                L410Keys.couponUsers(activityId),
                L410Keys.couponClaim(activityId, userId),
                L410Keys.couponSeq(activityId),
                L410Keys.couponRank(activityId)
        );
        String json = template.execute(
                script, keys,
                userId,
                couponCodePrefix,
                String.valueOf(now),
                String.valueOf(activityEndMs),
                String.valueOf(couponValidMs),
                String.valueOf(topN)
        );
        try {
            return mapper.readValue(json, CouponClaimResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Lua 返回 JSON 解析失败:" + json, e);
        }
    }

    public Long getRemainStock(String activityId) {
        String s = template.opsForValue().get(L410Keys.couponStock(activityId));
        return s == null ? null : Long.parseLong(s);
    }

    public Long getRank(String activityId, String userId) {
        Long r = template.opsForZSet().rank(L410Keys.couponRank(activityId), userId);
        return r == null ? null : r + 1;
    }

    public void clearCoupon(String activityId) {
        template.delete(Arrays.asList(
                L410Keys.couponStock(activityId),
                L410Keys.couponUsers(activityId),
                L410Keys.couponSeq(activityId),
                L410Keys.couponRank(activityId)
        ));
        // claim hash 因为是用户级,简化删除示例
        template.delete(Collections.singleton(L410Keys.couponClaim(activityId, "*")));
    }
}
