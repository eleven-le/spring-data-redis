package org.springframework.data.redis.laboratory.l4.l4_10.toushi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 偷师 ②：Template + Executor 模式（资源管理 vs 执行策略分离）。
 * <p>
 * Spring Data Redis：
 * <pre>
 *   RedisTemplate (门面 + 资源生命周期)
 *      └── ScriptExecutor (执行策略接口)
 *           └── DefaultScriptExecutor (evalSha/eval fallback)
 * </pre>
 * 业务调 {@code redisTemplate.execute(script, keys, args)}，模板拿连接、调用 executor、释放连接；
 * executor 决定"怎么执行"。把"资源生命周期"和"执行细节"分两层是 Spring 的招牌动作。
 * <p>
 * 任何业务侧的<b>资源型 + 多策略</b>场景都能照抄：
 * <ul>
 *   <li>三方支付查单：PaymentTemplate 拿 token、释放连接；不同支付通道用不同 PaymentExecutor</li>
 *   <li>规则引擎：RuleEngineTemplate 拿规则上下文；不同规则脚本用不同 RuleExecutor</li>
 * </ul>
 */
public class TemplateExecutorDesignDemo {

    public interface ResourceContext {
        Map<String, Object> bag();
    }

    public interface OperationExecutor<P, R> {
        R execute(ResourceContext ctx, P input);
    }

    /** 模板：负责资源 open/close + 把执行委派给 executor。 */
    public static class ResourceTemplate {
        public <P, R> R execute(OperationExecutor<P, R> executor, P input,
                                Function<Map<String, Object>, AutoCloseable> resourceFactory) {
            Map<String, Object> bag = new HashMap<>();
            try (AutoCloseable ignored = resourceFactory.apply(bag)) {
                ResourceContext ctx = () -> bag;
                return executor.execute(ctx, input);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** 一个具体 executor：把规则脚本"执行"出来（这里只是演示，不真的跑 Lua）。 */
    public static class RuleScriptExecutor implements OperationExecutor<List<String>, Boolean> {
        @Override
        public Boolean execute(ResourceContext ctx, List<String> rules) {
            ctx.bag().put("invokedAt", System.currentTimeMillis());
            return rules.stream().allMatch(r -> r.contains("ok"));
        }
    }

    public static void main(String[] args) {
        ResourceTemplate template = new ResourceTemplate();
        Boolean ok = template.execute(
                new RuleScriptExecutor(),
                List.of("score-ok", "fraud-ok", "kyc-ok"),
                bag -> () -> System.out.println("resource closed, bag=" + bag)
        );
        System.out.println("rule pass = " + ok);
    }
}
