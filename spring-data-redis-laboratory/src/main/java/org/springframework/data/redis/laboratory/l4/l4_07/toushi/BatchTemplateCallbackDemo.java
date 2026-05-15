package org.springframework.data.redis.laboratory.l4.l4_07.toushi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 偷师 Demo 1：把 RedisTemplate 的 "Template + Callback" 思想抽出来，迁移到业务场景。
 * <p>
 * <b>场景</b>：茶饮 C 端调三方支付查 N 个订单状态。原写法是 service 里 for 循环调 RestTemplate，
 * 每次都拿连接 / 失败处理 / 关闭散落到业务代码里。
 * <p>
 * <b>偷师点</b>：
 *   1) {@link BatchTemplate} 模拟 RedisTemplate：负责"获取连接 / 开 batch 模式 / 收集结果 / 关闭 batch / 释放资源"；
 *   2) {@link BatchCallback} 模拟 RedisCallback：业务只关心"在 batch 模式下做什么"；
 *   3) 模板方法 + 回调，整套把"资源生命周期"和"业务逻辑"分离。
 * <p>
 * 对照 RedisTemplate.executePipelined：
 * <pre>
 *     openPipeline()        ←→   BatchConnection.openBatch()
 *     callback.doInRedis()  ←→   callback.doInBatch()
 *     closePipeline()       ←→   BatchConnection.closeBatch()
 * </pre>
 */
public class BatchTemplateCallbackDemo {

    /**
     * 模拟"批量请求连接"。真实业务里它可能是 HTTP Client / Feign / RestTemplate / 第三方 SDK。
     */
    public static class BatchConnection {
        private final List<String> buffered = new ArrayList<>();
        private boolean batched;

        public void openBatch() {
            this.batched = true;
            this.buffered.clear();
            System.out.println("[BatchConnection] openBatch");
        }

        public void send(String request) {
            if (batched) {
                buffered.add(request);
            } else {
                System.out.println("[BatchConnection] sync send: " + request);
            }
        }

        public List<Object> closeBatch() {
            System.out.println("[BatchConnection] closeBatch flush " + buffered.size() + " requests");
            List<Object> results = new ArrayList<>();
            for (String req : buffered) {
                // 模拟一次性发送、一次性收响应
                results.add("resp-of-" + req);
            }
            this.batched = false;
            this.buffered.clear();
            return results;
        }
    }

    /**
     * 模拟"批量回调"。
     */
    @FunctionalInterface
    public interface BatchCallback<T> {
        T doInBatch(BatchConnection connection);
    }

    /**
     * 模板：负责资源生命周期。
     */
    public static class BatchTemplate {
        private final Function<Void, BatchConnection> connectionSupplier;

        public BatchTemplate(Function<Void, BatchConnection> connectionSupplier) {
            this.connectionSupplier = connectionSupplier;
        }

        public List<Object> executeBatch(BatchCallback<?> callback) {
            BatchConnection connection = null;
            try {
                connection = connectionSupplier.apply(null);
                connection.openBatch();
                callback.doInBatch(connection);
                return connection.closeBatch();
            } catch (RuntimeException ex) {
                System.err.println("[BatchTemplate] error: " + ex.getMessage());
                throw ex;
            } finally {
                // 真实业务这里要把连接还回连接池
                System.out.println("[BatchTemplate] release connection");
            }
        }
    }

    public static void main(String[] args) {
        BatchTemplate template = new BatchTemplate(v -> new BatchConnection());

        List<Object> results = template.executeBatch(connection -> {
            for (int i = 0; i < 5; i++) {
                connection.send("queryOrder#" + i);
            }
            return null;
        });
        System.out.println("results = " + results);
    }
}
