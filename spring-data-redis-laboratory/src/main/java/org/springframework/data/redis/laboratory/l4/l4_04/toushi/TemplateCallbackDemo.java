package org.springframework.data.redis.laboratory.l4.l4_04.toushi;

/**
 * 偷师 Template + Callback 模式。
 * <p>
 * RedisTemplate.execute 的精髓：
 * 1) 模板（Template）负责"获取资源 → finally 释放资源 → 异常翻译"——这些通用、烦人但必须做对；
 * 2) 回调（Callback）只关心一行真正的业务命令；
 * 3) 业务代码因此变得无重复样板代码（无 try/finally、无连接释放、无异常包装）。
 * <p>
 * 这个 Demo 把 Redis 换成"任意第三方资源"——可以是 HTTP 客户端、消息客户端、外部 API。
 * 同样的结构能立刻迁移到 C 端业务里：调用第三方支付、营销系统、风控系统时，
 * 用 Template 包资源管理，业务方法只写"这次具体要做什么"。
 */
public class TemplateCallbackDemo {

    /** 一次资源连接（仿 RedisConnection）。 */
    static class ResourceConnection implements AutoCloseable {
        boolean closed;
        public Object exec(String cmd, Object... args) {
            if (closed) throw new IllegalStateException("connection closed");
            return cmd + Arrays.toString(args);
        }
        @Override public void close() { closed = true; }
    }

    /** 业务回调（仿 RedisCallback）。 */
    @FunctionalInterface
    interface ResourceCallback<T> {
        T doInResource(ResourceConnection conn);
    }

    /** 模板（仿 RedisTemplate）。 */
    static class ResourceTemplate {
        public <T> T execute(ResourceCallback<T> callback) {
            ResourceConnection conn = openConnection(); // 等价 RedisConnectionUtils.doGetConnection
            try {
                return callback.doInResource(conn);
            } catch (RuntimeException ex) {
                // 异常翻译：屏蔽下游 SDK 的奇怪异常类型，统一抛业务异常
                throw new RuntimeException("[ResourceTemplate] failed: " + ex.getMessage(), ex);
            } finally {
                releaseConnection(conn);
            }
        }
        private ResourceConnection openConnection() {
            System.out.println("  >> openConnection");
            return new ResourceConnection();
        }
        private void releaseConnection(ResourceConnection c) {
            System.out.println("  << releaseConnection");
            c.close();
        }
    }

    public static void main(String[] args) {
        ResourceTemplate template = new ResourceTemplate();

        // 业务 1：发送营销短信。模板包资源，业务只关心命令。
        Object r1 = template.execute(conn -> conn.exec("SEND_SMS", "13800000000", "您有新优惠"));
        System.out.println("[业务 1] " + r1);

        // 业务 2：查询风控分。
        Object r2 = template.execute(conn -> conn.exec("RISK_QUERY", "user-1001"));
        System.out.println("[业务 2] " + r2);

        // 业务 3：故意抛异常，看模板如何包装并仍然释放资源。
        try {
            template.execute(conn -> { throw new IllegalStateException("downstream down"); });
        } catch (Exception ex) {
            System.out.println("[业务 3] caught=" + ex.getMessage());
        }
    }

    private static final class Arrays {
        static String toString(Object[] arr) {
            return java.util.Arrays.toString(arr);
        }
    }
}
