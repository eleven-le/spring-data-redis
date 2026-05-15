package org.springframework.data.redis.laboratory.l4.l4_10.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 优惠券领取脚本返回值。
 * <p>
 * code: 1 成功 / 0 库存不足 / -1 已领过(返回上次 claimId) / -2 活动结束 / -9 参数非法
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CouponClaimResult {
    public int code;
    public String msg;
    public String claimId;
    public String couponCode;
    public Long remain;
    public Long claimedTotal;
    public Long rank;
    public boolean earnBadge;
    public Long validUntilMs;
    public Long ts;

    public boolean isSuccess() { return code == 1; }

    @Override public String toString() {
        return String.format("CouponClaimResult{code=%d, msg=%s, claimId=%s, couponCode=%s, remain=%s, " +
                        "claimedTotal=%s, rank=%s, earnBadge=%s, validUntilMs=%s, ts=%s}",
                code, msg, claimId, couponCode, remain, claimedTotal, rank, earnBadge, validUntilMs, ts);
    }
}
