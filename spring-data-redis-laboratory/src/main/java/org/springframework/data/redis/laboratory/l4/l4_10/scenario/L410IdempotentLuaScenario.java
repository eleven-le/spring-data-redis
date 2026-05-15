package org.springframework.data.redis.laboratory.l4.l4_10.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.laboratory.l4.l4_10.L410Keys;
import org.springframework.data.redis.laboratory.l4.l4_10.result.IdempotentResult;

import java.time.Duration;
import java.util.Collections;
import java.util.function.Supplier;

/**
 * 生产级·业务级幂等门(支付回调 / MQ 重投 / HTTP 重试)。
 * <p>
 * 与 SET NX EX 的关键差别:重放时拿回上次的<b>业务结果</b>而不是 false。
 * 上游 retry 拿到上次的业务 JSON,可以直接当成功响应,不会触发更多 retry 雪崩。
 *
 * <h3>典型用法</h3>
 * <pre>
 * IdempotentResult ir = scenario.tryEnterFirstTime(biz, requestId, ttl, () -> "{\"status\":\"PROCESSING\"}");
 * if (!ir.firstTime) {
 *     return ir.previousResult;   // 重放:直接拿上次结果
 * }
 * String real = doBusiness(...);
 * scenario.commitResult(biz, requestId, ttl, real);  // 把真正的结果覆盖回去
 * return real;
 * </pre>
 */
public class L410IdempotentLuaScenario {

    private final StringRedisTemplate template;
    private final RedisScript<String> script;
    private final ObjectMapper mapper;

    public L410IdempotentLuaScenario(StringRedisTemplate template,
                                     RedisScript<String> idempotentMarkScript,
                                     ObjectMapper mapper) {
        this.template = template;
        this.script = idempotentMarkScript;
        this.mapper = mapper;
    }

    /**
     * 幂等门首入:返回 firstTime=true 表示是第一次,业务可继续;
     * 否则 previousResult 是上次的结果。
     *
     * @param initialResult 首次写入的占位 JSON,如 {"status":"PROCESSING","createdAt":...}
     */
    public IdempotentResult tryEnter(String biz, String requestId, Duration ttl, Supplier<String> initialResult) {
        long now = System.currentTimeMillis();
        String json = template.execute(
                script,
                Collections.singletonList(L410Keys.idem(biz, requestId)),
                requestId,
                String.valueOf(ttl.getSeconds()),
                String.valueOf(now),
                initialResult.get()
        );
        try {
            return mapper.readValue(json, IdempotentResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Lua 返回 JSON 解析失败:" + json, e);
        }
    }

    /** 业务完成后,把真实结果覆盖回幂等键,后续重放可拿到。 */
    public void commitResult(String biz, String requestId, Duration ttl, String resultJson) {
        template.opsForValue().set(L410Keys.idem(biz, requestId), resultJson, ttl);
    }

    public void clearMark(String biz, String requestId) {
        template.delete(L410Keys.idem(biz, requestId));
    }
}
