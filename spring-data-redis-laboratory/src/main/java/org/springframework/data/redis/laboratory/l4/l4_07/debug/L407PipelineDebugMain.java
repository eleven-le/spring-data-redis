package org.springframework.data.redis.laboratory.l4.l4_07.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_07.config.L407RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_07.pipeline.L407PipelineBasicOperationsLab;

import java.time.Duration;
import java.util.List;

/**
 * Pipeline 基础调试入口。
 * <p>
 * <b>建议断点位置</b>：
 * <ol>
 *   <li>{@code RedisTemplate#executePipelined(SessionCallback)} —— Pipeline 入口</li>
 *   <li>{@code RedisTemplate#executePipelined(RedisCallback)} —— RedisCallback 流派入口</li>
 *   <li>{@code RedisTemplate#execute(RedisCallback, boolean, boolean)} —— 真正的资源管理中枢</li>
 *   <li>{@code RedisConnectionUtils#doGetConnection} —— 看 Pipeline 怎么拿连接（必须独立连接）</li>
 *   <li>{@code LettuceConnection#openPipeline} —— 看 Lettuce 如何切到 pipeline 模式</li>
 *   <li>{@code LettuceConnection#closePipeline} —— 看 close 时怎么 awaitAll Future</li>
 *   <li>{@code RedisTemplate#deserializeMixedResults} —— 看 mixed results 反序列化策略</li>
 * </ol>
 */
public class L407PipelineDebugMain {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(L407RedisConfig.class)) {

            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);
            L407PipelineBasicOperationsLab lab = new L407PipelineBasicOperationsLab(template);

            System.out.println("===== L4-07 Pipeline Debug =====");
            // 1. 准备 100 个 demo key
            int n = 100;
            lab.prepareDemoData(n, Duration.ofMinutes(10));
            System.out.println("[prepare] 写入 " + n + " 个 demo key 完成");

            // 2. SessionCallback 批量 GET
            List<String> keys = L407PipelineBasicOperationsLab.demoKeyRange(0, n);
            List<Object> getResults = lab.pipelineBatchGetWithSessionCallback(keys);
            System.out.println("[session GET] size=" + getResults.size() + ", preview=" + L407PipelineBasicOperationsLab.previewList(getResults, 5));

            // 3. RedisCallback 批量 GET（断点 LettuceConnection#stringCommands）
            List<Object> rawResults = lab.pipelineBatchGetWithRedisCallback(keys);
            System.out.println("[redis-callback GET] size=" + rawResults.size() + ", preview=" + L407PipelineBasicOperationsLab.previewList(rawResults, 5));

            // 4. 混合命令
            List<Object> mixed = lab.pipelineMixedCommandsWithSessionCallback("l4:07:debug:user:1", "l4:07:debug:visit:home");
            System.out.println("[mixed] size=" + mixed.size() + ", values=" + mixed);

            // 5. 普通循环 vs Pipeline 性能对照
            long[] cost = lab.compareNormalLoopVsPipeline(keys);
            System.out.println("[compare] normalLoop=" + cost[0] + "ms, pipeline=" + cost[1] + "ms");

            // 6. 清理
            lab.cleanupDemoData(n);
            template.delete("l4:07:debug:user:1");
            template.delete("l4:07:debug:visit:home");

            System.out.println("===== Pipeline Debug Done =====");
        }
    }
}
