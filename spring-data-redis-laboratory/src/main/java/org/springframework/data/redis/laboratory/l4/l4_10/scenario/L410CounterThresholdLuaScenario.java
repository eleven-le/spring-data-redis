package org.springframework.data.redis.laboratory.l4.l4_10.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.laboratory.l4.l4_10.L410Keys;
import org.springframework.data.redis.laboratory.l4.l4_10.result.CounterThresholdResult;

import java.time.Duration;
import java.util.Collections;

/**
 * 生产级·计数器阈值限制(返回 当前/阈值/距离/告警水位/TTL)。
 * <p>
 * 与 INCR 后判断回滚的区别已在脚本内说明。生产级场景要点:
 * <ul>
 *   <li>{@code distance} 直接驱动前端"还能领 X 次"</li>
 *   <li>{@code alarm} 表示已到达告警水位(默认 80%),供监控系统拉曲线</li>
 *   <li>{@code ttlMs} 用于诊断"为什么这个用户还没刷新计数"</li>
 * </ul>
 */
public class L410CounterThresholdLuaScenario {

    private final StringRedisTemplate template;
    private final RedisScript<String> script;
    private final ObjectMapper mapper;

    public L410CounterThresholdLuaScenario(StringRedisTemplate template,
                                           RedisScript<String> counterThresholdScript,
                                           ObjectMapper mapper) {
        this.template = template;
        this.script = counterThresholdScript;
        this.mapper = mapper;
    }

    public CounterThresholdResult tryIncrease(String userId, String action, long delta, long threshold,
                                              Duration ttl, int alarmPercent) {
        long now = System.currentTimeMillis();
        String json = template.execute(
                script,
                Collections.singletonList(L410Keys.counter(userId, action)),
                String.valueOf(delta),
                String.valueOf(threshold),
                String.valueOf(ttl.getSeconds()),
                String.valueOf(alarmPercent),
                String.valueOf(now)
        );
        try {
            return mapper.readValue(json, CounterThresholdResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Lua 返回 JSON 解析失败:" + json, e);
        }
    }

    public Long getCurrent(String userId, String action) {
        String s = template.opsForValue().get(L410Keys.counter(userId, action));
        return s == null ? 0L : Long.parseLong(s);
    }

    public void clearCounter(String userId, String action) {
        template.delete(L410Keys.counter(userId, action));
    }
}
