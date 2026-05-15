package org.springframework.data.redis.laboratory.l4.l4_10.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 分布式锁安全释放返回值。
 * <p>
 * code: 1 正常释放 / 0 token 不匹配(currentHolder 是真实持有者,接告警) / -1 锁已过期或被释放 / -9 参数非法
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LockReleaseResult {
    public int code;
    public String msg;
    public boolean released;
    public Long heldMs;
    public String currentHolder;
    public Long ts;

    public boolean isReleased() { return released; }
    public boolean isAlertable() { return code == 0; }

    @Override public String toString() {
        return String.format("LockReleaseResult{code=%d, msg=%s, released=%s, heldMs=%s, currentHolder=%s, ts=%s}",
                code, msg, released, heldMs, currentHolder, ts);
    }
}
