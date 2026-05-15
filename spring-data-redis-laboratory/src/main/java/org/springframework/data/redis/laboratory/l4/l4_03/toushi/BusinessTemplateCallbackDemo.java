package org.springframework.data.redis.laboratory.l4.l4_03.toushi;

/**
 * 偷师 1：Template + Callback。
 * <p>
 * 把 Spring Data Redis 的 RedisTemplate.execute(RedisCallback) 思想套到"调用第三方 API"上。
 * <p>
 * 学到的核心：
 * <ul>
 *   <li>Template 负责资源生命周期：连接获取、计时、异常翻译、连接释放——固定流程；</li>
 *   <li>Callback 负责真正的业务命令——可变逻辑；</li>
 *   <li>调用方写业务时，不再操心连接如何拿、何时释放、异常如何翻——这就是 Spring 抽象的力量。</li>
 * </ul>
 * 对比 RedisTemplate#execute：
 * <pre>
 *   RedisTemplate.execute(RedisCallback)
 *     → preProcessConnection / getConnection
 *     → callback.doInRedis(conn)         // 用户真正想做的事
 *     → postProcessResult / releaseConnection (在 finally)
 * </pre>
 */
public class BusinessTemplateCallbackDemo {

    /** 模拟一个第三方资源，需要打开/关闭。 */
    static class BusinessResource implements AutoCloseable {
        private final String name;
        BusinessResource(String name) {
            this.name = name;
            System.out.println("  [资源] 打开 " + name);
        }
        public String call(String cmd) { return "RESP(" + name + "," + cmd + ")"; }
        @Override public void close() { System.out.println("  [资源] 关闭 " + name); }
    }

    @FunctionalInterface
    interface BusinessCallback<T> {
        T doInBusiness(BusinessResource resource);
    }

    /** 模板：固定流程。新人接入时只需关心 callback，不必学资源管理。 */
    static class BusinessTemplate {
        private final String endpoint;
        BusinessTemplate(String endpoint) { this.endpoint = endpoint; }

        public <T> T execute(BusinessCallback<T> callback) {
            BusinessResource resource = new BusinessResource(endpoint);
            long start = System.nanoTime();
            try {
                return callback.doInBusiness(resource);
            } catch (RuntimeException e) {
                // 这里就是 RedisTemplate 里 ExceptionTranslator 的位置 —— 把底层异常翻译成业务可读异常
                throw new IllegalStateException("调用 " + endpoint + " 失败", e);
            } finally {
                long cost = (System.nanoTime() - start) / 1_000_000;
                System.out.println("  [模板] 耗时 " + cost + "ms");
                resource.close();
            }
        }
    }

    public static void main(String[] args) {
        BusinessTemplate template = new BusinessTemplate("https://api.partner.example.com");

        // 业务方写的就这一行，回调里只关心"我要发什么命令"
        String result = template.execute(resource -> resource.call("queryOrder?id=O-10086"));
        System.out.println("业务结果: " + result);

        // 第二次调用，资源生命周期完全一致 —— 这就是模板方法 + 回调的复用力
        Object exists = template.execute(resource -> resource.call("checkExists?id=10086"));
        System.out.println("业务结果: " + exists);
    }
}
