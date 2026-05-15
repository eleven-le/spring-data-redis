package org.springframework.data.redis.laboratory.l4.l4_05.toushi;

import java.util.function.Function;

/**
 * 偷师 2：Template + Callback。
 * <p>
 * RedisTemplate.execute(RedisCallback) 把"获取连接 / 异常处理 / 释放连接"包成模板，
 * 业务只通过回调注入"我要发什么命令"。
 * <p>
 * 业务里调用第三方支付 / 风控 / 营销 SDK 时，几乎都该套这个骨架：
 * 模板管资源 + 回调写业务。
 * <p>
 * 抽象骨架：
 * <pre>
 *   ResourceTemplate.execute(callback):
 *      conn = factory.acquire();
 *      try { return callback.apply(conn); }
 *      catch (X e) { throw translate(e); }
 *      finally { factory.release(conn); }
 * </pre>
 */
public class TemplateCallbackDemo {

    /** 模板：管资源生命周期 + 异常翻译。 */
    public static class ResourceTemplate {
        private final ResourceFactory factory;

        public ResourceTemplate(ResourceFactory factory) {
            this.factory = factory;
        }

        public <T> T execute(ResourceCallback<T> callback) {
            ResourceConnection conn = factory.acquire();
            try {
                return callback.doInResource(conn);
            } catch (RuntimeException e) {
                throw translate(e);
            } finally {
                factory.release(conn);
            }
        }

        private RuntimeException translate(RuntimeException e) {
            // 业务侧把第三方异常翻译为统一异常。SDR 这里对应 ExceptionTranslationStrategy
            return new IllegalStateException("[Resource Failure] " + e.getMessage(), e);
        }
    }

    public interface ResourceFactory {
        ResourceConnection acquire();
        void release(ResourceConnection connection);
    }

    public interface ResourceConnection extends AutoCloseable {
        String executeRaw(String command);

        @Override
        default void close() { /* 模板会调 release，这里留空 */ }
    }

    @FunctionalInterface
    public interface ResourceCallback<T> {
        T doInResource(ResourceConnection conn);
    }

    /** 演示：内存 fake factory + connection。 */
    public static class FakeFactory implements ResourceFactory {
        @Override public ResourceConnection acquire() { return cmd -> "executed:" + cmd; }
        @Override public void release(ResourceConnection connection) { /* noop */ }
    }

    public static void main(String[] args) {
        ResourceTemplate template = new ResourceTemplate(new FakeFactory());
        // 业务 1：发个简单请求
        String r1 = template.execute(conn -> conn.executeRaw("PING"));
        // 业务 2：把组合逻辑塞进 callback（多步原子）
        Function<ResourceConnection, String> compound = conn -> {
            conn.executeRaw("BEGIN");
            String mid = conn.executeRaw("DO_BUSINESS");
            conn.executeRaw("COMMIT");
            return mid;
        };
        String r2 = template.execute(compound::apply);
        System.out.println("TemplateCallbackDemo: r1=" + r1 + ", r2=" + r2);
    }
}
