package org.springframework.data.redis.laboratory.l4.l4_08.toushi;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 偷师：SessionCallback 的"同会话资源"思想。
 * <p>
 * 把 Spring Data Redis 的 SessionCallback 抽象成更通用的 SessionTemplate：
 * <ul>
 *   <li>{@code SessionResource} —— 每次 callback 范围内独享的资源（连接 / 缓存批次 / 事务上下文 / Span 等）；</li>
 *   <li>{@code SessionCallback} —— 业务的可变行为，拿到资源后做事情；</li>
 *   <li>{@code SessionTemplate} —— 模板：负责资源获取、传给 callback、保证回收。</li>
 * </ul>
 * <p>
 * 你能学到的核心：<b>"同一资源 + 回调范围 + 自动回收"</b> 是框架最朴素也最好用的封装手法。
 * RedisTemplate / JdbcTemplate / TransactionTemplate / RestTemplate 都是同构。
 */
public class SessionTemplateDesignDemo {

    /** 模拟的资源：每次 acquire 拿到独立实例。 */
    public static final class SessionResource implements AutoCloseable {
        private static final AtomicInteger SEQ = new AtomicInteger(0);
        private final int id;
        private boolean closed;

        public SessionResource() {
            this.id = SEQ.incrementAndGet();
            System.out.println("[SessionResource] acquired #" + id);
        }
        public int getId() { return id; }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                System.out.println("[SessionResource] released #" + id);
            }
        }
    }

    @FunctionalInterface
    public interface SessionCallback<T> {
        T doInSession(SessionResource resource);
    }

    public static final class SessionTemplate {
        public <T> T execute(SessionCallback<T> callback) {
            // 模板方法：获取资源 → 调 callback → finally 释放
            try (SessionResource r = new SessionResource()) {
                return callback.doInSession(r);
            }
        }
    }

    public static void main(String[] args) {
        SessionTemplate template = new SessionTemplate();

        Integer result = template.execute(new SessionCallback<Integer>() {
            @Override
            public Integer doInSession(SessionResource resource) {
                System.out.println("  [biz] using resource #" + resource.getId() + " step1");
                System.out.println("  [biz] using resource #" + resource.getId() + " step2");
                System.out.println("  [biz] using resource #" + resource.getId() + " step3");
                return resource.getId() * 100;
            }
        });
        System.out.println("[demo] result = " + result);

        // 用 lambda + Function 做更紧凑写法
        Function<SessionResource, String> action = r -> "value-from-" + r.getId();
        String r2 = template.execute(action::apply);
        System.out.println("[demo] r2 = " + r2);
    }
}
