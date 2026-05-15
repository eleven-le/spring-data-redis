package org.springframework.data.redis.laboratory.l4.l4_07.toushi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 偷师 Demo 3：通用"结果映射器"。
 * <p>
 * <b>场景</b>：批量调用返回的是 List&lt;Object&gt;，业务总不能 service 里到处 (Long)、(String) 强转。
 * 应该集中维护"第 i 条命令是什么 / 第 i 条结果应该被怎么解析"，对应 Pipeline 的 mixed results。
 * <p>
 * <b>偷师点</b>：
 *   1) {@link CommandPlan}：维护命令计划（顺序 + 类型 + 业务标识）；
 *   2) {@link CommandResult}：把第 i 条结果按业务语义包装；
 *   3) {@link ResultMapper}：从原始 List&lt;Object&gt; → Map&lt;businessKey, CommandResult&gt;。
 * <p>
 * 对照 Pipeline：业务方法签名应该是 {@code HomePageAggregate getHomePage(...)}，
 * 而不是 {@code List&lt;Object&gt; getHomePagePipelineResults(...)}。
 */
public class ResultMapperDesignDemo {

    public enum CommandKind { GET, INCR, HGET, ZREVRANGE, SCARD }

    public record CommandStep(int index, CommandKind kind, String businessKey) { }

    public static class CommandPlan {
        private final List<CommandStep> steps = new ArrayList<>();
        public CommandPlan add(CommandKind kind, String businessKey) {
            steps.add(new CommandStep(steps.size(), kind, businessKey));
            return this;
        }
        public List<CommandStep> steps() { return steps; }
    }

    public record CommandResult(CommandStep step, Object raw) {
        public Long asLong() {
            return raw == null ? null : ((Number) raw).longValue();
        }
        public String asString() {
            return raw == null ? null : raw.toString();
        }
    }

    public static class ResultMapper {
        public Map<String, CommandResult> map(CommandPlan plan, List<Object> rawResults) {
            if (plan.steps().size() != rawResults.size()) {
                throw new IllegalStateException("plan size != results size: "
                        + plan.steps().size() + " vs " + rawResults.size());
            }
            Map<String, CommandResult> mapped = new LinkedHashMap<>();
            for (CommandStep step : plan.steps()) {
                mapped.put(step.businessKey() + "#" + step.kind(),
                        new CommandResult(step, rawResults.get(step.index())));
            }
            return mapped;
        }
    }

    public static void main(String[] args) {
        CommandPlan plan = new CommandPlan()
                .add(CommandKind.GET, "user:1:nick")
                .add(CommandKind.HGET, "user:1:profile")
                .add(CommandKind.INCR, "api:home:visit")
                .add(CommandKind.SCARD, "activity:1:join");

        // 模拟 pipeline 结果
        List<Object> rawResults = List.of("leiyuhang", "VIP6", 1024L, 1230L);

        Map<String, CommandResult> mapped = new ResultMapper().map(plan, rawResults);
        for (Map.Entry<String, CommandResult> e : mapped.entrySet()) {
            System.out.println(e.getKey() + " -> " + e.getValue().raw());
        }
    }
}
