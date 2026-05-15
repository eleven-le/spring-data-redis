package org.springframework.data.redis.laboratory.l4.l4_07.pipeline;

import org.springframework.data.redis.laboratory.l4.l4_07.pipeline.L407Pipelines;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_07.L407Keys;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L4-07 Pipeline 结果映射实验。
 * <p>
 * Pipeline 返回的是一组"按命令发送顺序排列"的结果。业务最常踩坑的就是：
 * - 不知道结果顺序就乱强转；
 * - RedisCallback 模式忘了反序列化；
 * - 把 INCR 的 Long 强转成 String；
 * - 一个 Pipeline 里混了 SET/INCR/GET 后，把整个 List 强转成 List&lt;String&gt;。
 * <p>
 * 核心原则：
 * 1) Pipeline 是一个 command plan，业务必须自己维护"第 i 条命令是什么";
 * 2) 结果通过 index 还原成业务结构（Map / 自定义对象）；
 * 3) RedisCallback 模式拿到的可能是 byte[]，需要 resultSerializer 或自己反序列化。
 */
public class L407PipelineResultMappingLab {

    private final StringRedisTemplate template;

    public L407PipelineResultMappingLab(StringRedisTemplate template) {
        this.template = template;
    }

    // ========================================================================
    // 1. 正确的结果映射示范：批量 GET 还原成 Map<key, value>
    // ========================================================================

    /**
     * 批量查询多个 key，返回 Map&lt;key, value&gt;。
     * <p>
     * 关键点：
     * 1) results.get(i) 对应 keys.get(i)；
     * 2) 不存在的 key 对应 null，业务要自行决定 fallback；
     * 3) 用 LinkedHashMap 保留 keys 的原始顺序，便于排查和接口返回。
     */
    public Map<String, String> mapBatchGetResults(List<String> keys) {
        List<Object> results = L407Pipelines.run(template, ops -> {
            for (String key : keys) {
                ops.opsForValue().get(key);
            }
            return null;
        });
        Map<String, String> map = new LinkedHashMap<>(keys.size() * 2);
        for (int i = 0; i < keys.size(); i++) {
            Object raw = results.get(i);
            map.put(keys.get(i), (raw == null) ? null : raw.toString());
        }
        return map;
    }

    // ========================================================================
    // 2. 混合命令的结果映射：用 CommandPlan 维护"第 i 条命令是什么"
    // ========================================================================

    /**
     * 一条命令计划：发送顺序 + 命令类型 + 业务标识。
     */
    public record CommandStep(int index, CommandKind kind, String businessKey) {
    }

    public enum CommandKind {GET, INCR, EXPIRE, SET, HGET}

    /**
     * 一个完整命令计划：执行 Pipeline 时和读取结果时都按这个顺序。
     */
    public static final class CommandPlan {
        private final List<CommandStep> steps = new ArrayList<>();

        public void add(CommandKind kind, String businessKey) {
            steps.add(new CommandStep(steps.size(), kind, businessKey));
        }

        public List<CommandStep> steps() {
            return steps;
        }
    }

    /**
     * 演示：执行混合命令并把结果按 plan 映射成 Map&lt;businessKey, Object&gt;。
     */
    public Map<String, Object> mapMixedCommandResults(CommandPlan plan) {
        List<Object> results = L407Pipelines.run(template, ops -> {
            for (CommandStep step : plan.steps()) {
                switch (step.kind()) {
                    case GET -> ops.opsForValue().get(step.businessKey());
                    case INCR -> ops.opsForValue().increment(step.businessKey());
                    case EXPIRE -> ops.expire(step.businessKey(), Duration.ofSeconds(60));
                    case SET -> ops.opsForValue().set(step.businessKey(), "demo-value");
                    case HGET -> ops.opsForHash().get(step.businessKey(), "field");
                }
            }
            return null;
        });

        Map<String, Object> mapped = new LinkedHashMap<>();
        for (CommandStep step : plan.steps()) {
            mapped.put(step.businessKey() + "#" + step.kind(), results.get(step.index()));
        }
        return mapped;
    }

    // ========================================================================
    // 3. 演示：返回顺序 == 命令发送顺序
    // ========================================================================

    /**
     * 强行打乱发送顺序，证明返回结果"严格按照发送顺序"。
     * 返回 List 长度 == 5，依次是：
     * GET k0、GET k4、GET k2、GET k1、GET k3
     */
    public List<Object> demonstrateResultOrder() {
        // 先准备 5 个 key
        List<String> keys = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            String k = L407Keys.labKey(i);
            keys.add(k);
            template.opsForValue().set(k, "v-" + i, Duration.ofMinutes(5));
        }

        return L407Pipelines.run(template, ops -> {
            ops.opsForValue().get(keys.get(0));
            ops.opsForValue().get(keys.get(4));
            ops.opsForValue().get(keys.get(2));
            ops.opsForValue().get(keys.get(1));
            ops.opsForValue().get(keys.get(3));
            return null;
        });
    }

    // ========================================================================
    // 4. 错误示范：直接强转 List<String>
    // ========================================================================

    /**
     * <b>反例</b>：把 SET + GET + INCR 混合 Pipeline 的结果强转 List&lt;String&gt;。
     * <p>
     * 实际返回值：[true(SET), "demo"(GET), 1L(INCR)]。强转 (List&lt;String&gt;) 会在
     * 第一次取出 boolean / Long 调用 String 方法时抛 ClassCastException。
     * <p>
     * 这里我们故意捕获并把异常信息打印出来，让学员看清楚问题。
     */
    public String demonstrateWrongCastPitfall() {
        String key = L407Keys.labKey(99);
        String counterKey = L407Keys.labKey(98);
        template.opsForValue().set(key, "demo", Duration.ofMinutes(5));
        template.delete(counterKey);

        List<Object> results = L407Pipelines.run(template, ops -> {
            ops.opsForValue().set(key, "demo");
            ops.opsForValue().get(key);
            ops.opsForValue().increment(counterKey);
            return null;
        });

        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            List<String> wrong = (List) results;
            // 真正触发异常的是后续把 element 当 String 用
            String first = wrong.get(2);
            // 让 first.length() 真的把 Long 当 String 用，强制抛 CCE
            return "(unexpected) first.length=" + first.length();
        } catch (ClassCastException e) {
            return "命中预期 ClassCastException: " + e.getMessage()
                    + "  原因: results 是 List<Object>，按 index 取值后必须按命令类型分别处理。";
        }
    }

    // ========================================================================
    // 5. 错误示范：RedisCallback 下忘了反序列化导致拿到 byte[]
    // ========================================================================

    /**
     * RedisCallback Pipeline 默认结果会经过 valueSerializer 反序列化。
     * <p>
     * 但是！只要传入了 resultSerializer 为 null，或对结果类型理解错误，就会拿到 byte[]。
     * <p>
     * 这里通过 executePipelined(RedisCallback, RedisSerializer) 显式传 null serializer，
     * 把"未反序列化的 byte[] 是什么样"亲自展示给学员。
     * <p>
     * 注意：传 null 是允许的，含义就是"不要给我自动反序列化，我自己处理"。
     */
    public List<Object> demonstrateSerializerPitfall(List<String> keys) {
        @SuppressWarnings("unchecked")
        RedisSerializer<String> keySer = (RedisSerializer<String>) template.getKeySerializer();

        // 第二个参数 RedisSerializer<?> = null：返回的 byte[] 不会被 SDR 反序列化
        return template.executePipelined((RedisCallback<Object>) connection -> {
            for (String key : keys) {
                connection.stringCommands().get(keySer.serialize(key));
            }
            return null;
        }, /* resultSerializer = */ null);
    }

    /**
     * 把 byte[] 结果手动转成 String，演示如何"挽救"上面的反例。
     */
    public List<String> manualDeserializeByteResults(List<Object> rawResults) {
        List<String> out = new ArrayList<>(rawResults.size());
        for (Object o : rawResults) {
            if (o == null) {
                out.add(null);
            } else if (o instanceof byte[] bytes) {
                out.add(new String(bytes, StandardCharsets.UTF_8));
            } else {
                out.add(o.toString());
            }
        }
        return out;
    }

    /**
     * 仅供测试：把 results 里的 boolean / Long / String 用统一 Object#toString 打印一遍，
     * 让学员看到 mixed results 的真实形态。
     */
    public List<String> previewMixedTypes(List<Object> results) {
        List<String> out = new ArrayList<>(results.size());
        for (Object o : results) {
            if (o == null) {
                out.add("[null]");
            } else if (o instanceof byte[] bytes) {
                out.add("[byte[]] " + new String(bytes, StandardCharsets.UTF_8));
            } else {
                out.add("[" + o.getClass().getSimpleName() + "] " + o);
            }
        }
        return out;
    }

    /**
     * 仅供 demo：触发一次"业务正确处理 mixed results"的小场景。
     */
    public BusinessAggregate parseHomePageMixedResults(List<Object> results) {
        // results[0] = SET 结果，应当忽略
        // results[1] = GET 用户昵称
        // results[2] = INCR 访问次数
        BusinessAggregate aggregate = new BusinessAggregate();
        aggregate.nick = (results.get(1) == null) ? null : results.get(1).toString();
        aggregate.visit = (results.get(2) == null) ? 0L : ((Number) results.get(2)).longValue();
        return aggregate;
    }

    public static class BusinessAggregate {
        public String nick;
        public long visit;

        @Override
        public String toString() {
            return "BusinessAggregate{nick='" + nick + "', visit=" + visit + '}';
        }
    }

    /**
     * 工具：兜底捕获并把 DataAccessException 信息原样输出。
     */
    public static String describe(DataAccessException ex) {
        return ex.getClass().getSimpleName() + " : " + ex.getMessage();
    }
}
