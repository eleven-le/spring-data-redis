package org.springframework.data.redis.laboratory.l4.l4_03.toushi;

/**
 * 偷师 2：按业务能力拆 Operations。
 * <p>
 * RedisTemplate 不会把 set/get/incr/hset/zadd/zrange/lpush/sadd 全部塞在一个类里，
 * 而是按 Redis 数据结构拆出 ValueOperations / HashOperations / ListOperations / ZSetOperations / SetOperations，
 * 每个 Operations 接口职责单一，调用者按需取用：
 * <pre>
 *   template.opsForValue().set(...)
 *   template.opsForHash().put(...)
 * </pre>
 * <p>
 * 这套模式可以直接搬到业务系统：
 * <ul>
 *   <li>AppClient 是门面，统一持有底层资源；</li>
 *   <li>UserOperations / OrderOperations 是按业务能力拆的子接口，参数语义更贴近业务，
 *       新人读 UserOperations.queryByPhone 比读 AppClient.exec("USER_QUERY_BY_PHONE", phone) 直观得多。</li>
 * </ul>
 */
public class OperationsSplitDesignDemo {

    /** 共享的底层"连接/SDK"。在 Redis 里对应 RedisConnection。 */
    static class TransportClient {
        public String send(String resource, String op, String payload) {
            return "[transport] " + resource + " " + op + " " + payload;
        }
    }

    interface UserOperations {
        String queryByPhone(String phone);
        boolean updateNickname(String userId, String nickname);
    }

    interface OrderOperations {
        String queryById(String orderId);
        String cancel(String orderId, String reason);
    }

    static class DefaultUserOperations implements UserOperations {
        private final TransportClient transport;
        DefaultUserOperations(TransportClient transport) { this.transport = transport; }
        @Override public String queryByPhone(String phone) {
            return transport.send("user", "queryByPhone", phone);
        }
        @Override public boolean updateNickname(String userId, String nickname) {
            transport.send("user", "updateNickname", userId + ":" + nickname);
            return true;
        }
    }

    static class DefaultOrderOperations implements OrderOperations {
        private final TransportClient transport;
        DefaultOrderOperations(TransportClient transport) { this.transport = transport; }
        @Override public String queryById(String orderId) {
            return transport.send("order", "queryById", orderId);
        }
        @Override public String cancel(String orderId, String reason) {
            return transport.send("order", "cancel", orderId + ":" + reason);
        }
    }

    /**
     * 门面，对应 RedisTemplate。注意 ops* 方法 —— 子门面是"按需返回"，
     * 内部可以缓存（DefaultValueOperations 就是这么做的）。
     */
    static class AppClient {
        private final TransportClient transport;
        private final UserOperations  userOps;
        private final OrderOperations orderOps;

        AppClient(TransportClient transport) {
            this.transport = transport;
            this.userOps  = new DefaultUserOperations(transport);
            this.orderOps = new DefaultOrderOperations(transport);
        }

        public UserOperations  opsForUser()  { return userOps; }
        public OrderOperations opsForOrder() { return orderOps; }
    }

    public static void main(String[] args) {
        AppClient client = new AppClient(new TransportClient());

        // 业务读起来非常直观：动词 + 名词，不必先看 SDK 文档背命令字
        System.out.println(client.opsForUser().queryByPhone("13800000000"));
        System.out.println(client.opsForOrder().queryById("O-10086"));
        System.out.println(client.opsForOrder().cancel("O-10086", "用户取消"));
    }
}
