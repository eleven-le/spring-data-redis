package org.springframework.data.redis.laboratory.l4.l4_07.toushi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 偷师 Demo 4：批量查询策略。
 * <p>
 * <b>场景</b>：业务"查 N 个 key"在不同环境下应该用不同策略：
 *   - 同前缀同结构：MGET；
 *   - 跨结构 / 跨命令：Pipeline；
 *   - key 数量极少：直接循环就够；
 *   - 跨 cluster slot：必须按 slot 分组分别 Pipeline。
 * <p>
 * <b>偷师点</b>：
 *   1) {@link BatchQueryStrategy} 抽象出"查询策略"；
 *   2) {@link NormalLoopStrategy}、{@link MgetStrategy}、{@link PipelineStrategy} 是不同实现；
 *   3) {@link BatchQueryService} 只持有 BatchQueryStrategy，外部按场景注入。
 * <p>
 * 对照 Pipeline：业务不要把"用什么命令"硬编码在 service 里，应该让 service 持有策略，
 * 这样上线后压测发现某个场景 MGET 更优时，只换策略 bean 即可，不动业务代码。
 */
public class BatchStrategyDemo {

    public interface BatchQueryStrategy {
        Map<String, String> query(List<String> keys);
        String name();
    }

    public static class NormalLoopStrategy implements BatchQueryStrategy {
        @Override
        public Map<String, String> query(List<String> keys) {
            Map<String, String> map = new LinkedHashMap<>();
            for (String k : keys) {
                map.put(k, "loop-" + k); // 模拟 RTT
            }
            return map;
        }
        @Override
        public String name() { return "NormalLoop"; }
    }

    public static class MgetStrategy implements BatchQueryStrategy {
        @Override
        public Map<String, String> query(List<String> keys) {
            // 模拟"一条命令拿回所有 value"
            Map<String, String> map = new LinkedHashMap<>();
            for (String k : keys) {
                map.put(k, "mget-" + k);
            }
            return map;
        }
        @Override
        public String name() { return "MGET"; }
    }

    public static class PipelineStrategy implements BatchQueryStrategy {
        @Override
        public Map<String, String> query(List<String> keys) {
            Map<String, String> map = new LinkedHashMap<>();
            for (String k : keys) {
                map.put(k, "pipeline-" + k);
            }
            return map;
        }
        @Override
        public String name() { return "Pipeline"; }
    }

    public static class BatchQueryService {
        private BatchQueryStrategy strategy;

        public BatchQueryService(BatchQueryStrategy strategy) { this.strategy = strategy; }
        public void setStrategy(BatchQueryStrategy strategy) { this.strategy = strategy; }

        public Map<String, String> queryAll(List<String> keys) {
            System.out.println("[BatchQueryService] using strategy = " + strategy.name());
            return strategy.query(keys);
        }
    }

    public static void main(String[] args) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 5; i++) keys.add("user:" + i);

        BatchQueryService service = new BatchQueryService(new NormalLoopStrategy());
        System.out.println(service.queryAll(keys));

        service.setStrategy(new MgetStrategy());
        System.out.println(service.queryAll(keys));

        service.setStrategy(new PipelineStrategy());
        System.out.println(service.queryAll(keys));
    }
}
