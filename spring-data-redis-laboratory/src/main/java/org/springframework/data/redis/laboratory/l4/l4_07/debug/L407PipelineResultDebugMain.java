package org.springframework.data.redis.laboratory.l4.l4_07.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_07.config.L407RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_07.pipeline.L407PipelineBasicOperationsLab;
import org.springframework.data.redis.laboratory.l4.l4_07.pipeline.L407PipelineResultMappingLab;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pipeline 结果映射调试入口。
 * <p>
 * <b>建议断点位置</b>：
 * <ol>
 *   <li>{@code RedisTemplate#executePipelined(RedisCallback, RedisSerializer)} —— 看带 serializer 重载</li>
 *   <li>{@code RedisTemplate#deserializeMixedResults}</li>
 *   <li>{@code RedisTemplate#deserializeMixedResult} —— 单条结果反序列化</li>
 *   <li>{@code GenericJackson2JsonRedisSerializer#deserialize} —— Jackson 路径</li>
 * </ol>
 */
public class L407PipelineResultDebugMain {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(L407RedisConfig.class)) {
            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);
            L407PipelineBasicOperationsLab lab = new L407PipelineBasicOperationsLab(template);
            L407PipelineResultMappingLab map = new L407PipelineResultMappingLab(template);

            System.out.println("===== L4-07 Pipeline Result Debug =====");

            // 1. 写入 5 个 key，然后乱序 GET，验证返回顺序
            lab.prepareDemoData(5, Duration.ofMinutes(10));
            List<Object> ordered = map.demonstrateResultOrder();
            System.out.println("[order] " + ordered);

            // 2. 正确映射：批量 GET 还原 Map
            List<String> keys = L407PipelineBasicOperationsLab.demoKeyRange(0, 5);
            Map<String, String> kv = map.mapBatchGetResults(keys);
            System.out.println("[mapBatchGet] " + kv);

            // 3. 演示 ClassCastException
            String wrongCastInfo = map.demonstrateWrongCastPitfall();
            System.out.println("[wrongCast] " + wrongCastInfo);

            // 4. 演示 byte[] 反序列化坑
            List<Object> raw = map.demonstrateSerializerPitfall(keys);
            System.out.println("[byte[]] preview=" + L407PipelineBasicOperationsLab.previewByteArrayResults(raw, 3));
            List<String> recovered = map.manualDeserializeByteResults(raw);
            System.out.println("[manualDeserialize] " + recovered);

            // 5. 混合命令计划 + 结果映射
            L407PipelineResultMappingLab.CommandPlan plan = new L407PipelineResultMappingLab.CommandPlan();
            plan.add(L407PipelineResultMappingLab.CommandKind.SET, "l4:07:result:debug:k1");
            plan.add(L407PipelineResultMappingLab.CommandKind.GET, "l4:07:result:debug:k1");
            plan.add(L407PipelineResultMappingLab.CommandKind.INCR, "l4:07:result:debug:counter");
            Map<String, Object> mapped = map.mapMixedCommandResults(plan);
            System.out.println("[mappedPlan] " + new LinkedHashMap<>(mapped));

            // 清理
            lab.cleanupDemoData(5);
            template.delete("l4:07:result:debug:k1");
            template.delete("l4:07:result:debug:counter");

            System.out.println("===== Pipeline Result Debug Done =====");
        }
    }
}
