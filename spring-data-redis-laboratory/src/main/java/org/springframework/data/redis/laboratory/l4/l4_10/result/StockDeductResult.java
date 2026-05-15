package org.springframework.data.redis.laboratory.l4.l4_10.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 秒杀库存扣减脚本返回值的强类型 DTO。
 * <p>
 * 字段对应 stock_deduct.lua 的 cjson.encode 输出。
 * <ul>
 *   <li>{@code code}: 1 成功 / 0 库存不足 / -1 SKU 未初始化 / -2 用户超限 / -3 活动结束 / -4 幂等命中(原值) / -9 参数非法</li>
 *   <li>{@code idempotent}: 是否为幂等命中(同一 requestId 第二次进来)</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StockDeductResult {
    public int code;
    public String msg;
    public String txId;
    public Long qty;
    public Long remain;
    public Long userPurchased;
    public Long userLimitLeft;
    public Long ts;
    public boolean idempotent;

    public boolean isSuccess() { return code == 1; }
    public boolean isReplay()  { return idempotent; }

    @Override public String toString() {
        return String.format("StockDeductResult{code=%d, msg=%s, txId=%s, qty=%s, remain=%s, userPurchased=%s, " +
                        "userLimitLeft=%s, idempotent=%s, ts=%s}",
                code, msg, txId, qty, remain, userPurchased, userLimitLeft, idempotent, ts);
    }
}
