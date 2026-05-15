package org.springframework.data.redis.laboratory.l4.l4_10.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.laboratory.l4.l4_10.L410Keys;
import org.springframework.data.redis.laboratory.l4.l4_10.result.DelayQueueClaimResult;

import java.util.Arrays;
import java.util.List;

/**
 * 生产级·延迟队列原子抢占(含 任务详情 / attempts / visibility timeout / 死信)。
 * <p>
 * 茶饮 "15 分钟未支付自动取消" 真实链路:
 * <pre>
 *   下单 → ZADD ready score=triggerAt + HSET detail field=taskId 值=订单JSON
 *   定时器 → 调用 claim → 抢到的任务直接拿 payload 处理
 *   消费成功 → ZREM inflight
 *   消费失败/超时 → inflight 中 score 已到的任务被回扫 zset 重新放回 ready
 *   超过 maxAttempts → 进 dead letter 由人工处理
 * </pre>
 * <p>
 * Lua 只保证"抢占阶段"原子,不解决"业务幂等",消费者仍要保证下游幂等(DB 唯一键 / Outbox 等)。
 */
public class L410DelayQueueClaimLuaScenario {

    private final StringRedisTemplate template;
    private final RedisScript<String> script;
    private final ObjectMapper mapper;
    private final String biz;

    public L410DelayQueueClaimLuaScenario(StringRedisTemplate template,
                                          RedisScript<String> delayQueueClaimScript,
                                          ObjectMapper mapper,
                                          String biz) {
        this.template = template;
        this.script = delayQueueClaimScript;
        this.mapper = mapper;
        this.biz = biz;
    }

    /**
     * 投递任务:同时写 ready zset(score=触发时间) 和 detail hash(payload JSON)。
     */
    public void addTask(String taskId, String payloadJson, long executeAtMillis) {
        template.opsForZSet().add(L410Keys.delayReady(biz), taskId, executeAtMillis);
        template.opsForHash().put(L410Keys.delayDetail(biz), taskId, payloadJson);
    }

    /** 抢占到期任务,返回结构化结果(含每个任务的 payload + 尝试次数 + visibility deadline + 死信)。 */
    public DelayQueueClaimResult claimDueTasks(int limit, long visibilityMs, int maxAttempts) {
        long now = System.currentTimeMillis();
        List<String> keys = Arrays.asList(
                L410Keys.delayReady(biz),
                L410Keys.delayDetail(biz),
                L410Keys.delayAttempts(biz),
                L410Keys.delayInflight(biz),
                L410Keys.delayDead(biz)
        );
        String json = template.execute(
                script, keys,
                String.valueOf(now),
                String.valueOf(limit),
                String.valueOf(visibilityMs),
                String.valueOf(maxAttempts)
        );
        try {
            return mapper.readValue(json, DelayQueueClaimResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Lua 返回 JSON 解析失败:" + json, e);
        }
    }

    /** 业务消费成功后,从 inflight 移除并清理 detail。 */
    public void ackTask(String taskId) {
        template.opsForZSet().remove(L410Keys.delayInflight(biz), taskId);
        template.opsForHash().delete(L410Keys.delayDetail(biz), taskId);
        template.opsForHash().delete(L410Keys.delayAttempts(biz), taskId);
    }

    public void clearQueue() {
        template.delete(Arrays.asList(
                L410Keys.delayReady(biz),
                L410Keys.delayDetail(biz),
                L410Keys.delayAttempts(biz),
                L410Keys.delayInflight(biz),
                L410Keys.delayDead(biz)
        ));
    }
}
