package org.springframework.data.redis.laboratory.l4.l4_10.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.laboratory.l4.l4_10.L410Keys;
import org.springframework.data.redis.laboratory.l4.l4_10.result.LockReleaseResult;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 生产级·分布式锁安全释放(含持有时长 + 当前持有者诊断)。
 * <p>
 * 真实事故案例:
 * <ul>
 *   <li>A 拿锁 → A 业务跑超 TTL → 锁过期 → B 抢到 → A DEL 锁 → 误删 B 的锁。
 *       生产级释放<b>必须</b>在脚本内 GET 比较 token 后才 DEL,且 release 必须返回:
 *       (1) 是否真释放 (2) 持有时长 (3) 当前持有者(如果不是你的)。</li>
 *   <li>持有时长是排查"业务跑太久导致锁被自动释放"的关键证据,接告警监控。</li>
 * </ul>
 * <p>
 * 完整工程级分布式锁(看门狗续期 / 可重入 / RedLock)推荐 Redisson,不要自造大轮子。
 */
public class L410LockReleaseLuaScenario {

    private final StringRedisTemplate template;
    private final RedisScript<String> releaseScript;
    private final ObjectMapper mapper;

    public L410LockReleaseLuaScenario(StringRedisTemplate template,
                                      RedisScript<String> lockReleaseScript,
                                      ObjectMapper mapper) {
        this.template = template;
        this.releaseScript = lockReleaseScript;
        this.mapper = mapper;
    }

    /** 加锁:SET NX EX 单命令原子,无需 Lua。同时写元数据 hash 记录 acquiredAt。 */
    public boolean tryLock(String biz, String resourceId, String token, Duration ttl) {
        Boolean ok = template.opsForValue().setIfAbsent(L410Keys.lock(biz, resourceId), token, ttl);
        if (Boolean.TRUE.equals(ok)) {
            template.opsForHash().put(L410Keys.lockMeta(biz, resourceId),
                    "acquiredAt", String.valueOf(System.currentTimeMillis()));
            template.expire(L410Keys.lockMeta(biz, resourceId), ttl);
            return true;
        }
        return false;
    }

    /** 释放:返回结构化诊断结果(released / heldMs / currentHolder)。 */
    public LockReleaseResult releaseLock(String biz, String resourceId, String token) {
        long now = System.currentTimeMillis();
        List<String> keys = Arrays.asList(
                L410Keys.lock(biz, resourceId),
                L410Keys.lockMeta(biz, resourceId)
        );
        String json = template.execute(releaseScript, keys, token, String.valueOf(now));
        try {
            return mapper.readValue(json, LockReleaseResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Lua 返回 JSON 解析失败:" + json, e);
        }
    }

    public void clearLock(String biz, String resourceId) {
        template.delete(Collections.singletonList(L410Keys.lock(biz, resourceId)));
        template.delete(Collections.singletonList(L410Keys.lockMeta(biz, resourceId)));
    }

    /** 反例 - 直接 DEL 别人锁,仅教学用。 */
    public void unsafeDeleteExample(String biz, String resourceId) {
        template.delete(L410Keys.lock(biz, resourceId));
    }
}
