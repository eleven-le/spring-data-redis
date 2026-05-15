package org.springframework.data.redis.laboratory.l4.l4_10.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 通用幂等门返回值。
 * <p>
 * 与 SET NX EX 的区别:重放时也会带回上次写入的 previousResult,业务上游不会误判为失败。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class IdempotentResult {
    public boolean firstTime;
    public int code;
    public String msg;
    /** 重放时:上次写入的业务结果(任意 JSON 结构,所以用 JsonNode) */
    public JsonNode previousResult;
    public Long ttlMsLeft;
    public Long createdAt;
    public Long ts;

    public boolean isSuccess() { return code == 1; }
    public boolean isReplay()  { return !firstTime; }

    @Override public String toString() {
        return String.format("IdempotentResult{firstTime=%s, code=%d, msg=%s, previousResult=%s, " +
                        "ttlMsLeft=%s, createdAt=%s, ts=%s}",
                firstTime, code, msg, previousResult, ttlMsLeft, createdAt, ts);
    }
}
