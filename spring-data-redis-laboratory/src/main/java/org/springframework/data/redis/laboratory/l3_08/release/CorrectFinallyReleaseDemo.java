package org.springframework.data.redis.laboratory.l3_08.release;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisConnectionUtils;
import org.springframework.data.redis.laboratory.l3_08.support.L308RedisConfig;

/**
 * <h3>L3-08 / 🔚 释放阶段 · 实验 1:try-finally 是 SDR 的金科玉律</h3>
 *
 * <p>{@code RedisConnectionUtils#releaseConnection} 是 SDR 团队最重要的一行代码 —
 * 它是「连接生命周期闭环」的最后一公里。<b>跳过它,池就会泄漏</b>。</p>
 *
 * <h4>正确范式</h4>
 * <pre><code>
 * RedisConnection conn = RedisConnectionUtils.getConnection(factory);
 * try {
 *     // 业务命令
 * } finally {
 *     RedisConnectionUtils.releaseConnection(conn, factory);  // 必须!
 * }
 * </code></pre>
 *
 * <h4>偷师点</h4>
 * <ul>
 *   <li>{@code DataSourceUtils.releaseConnection} 是同款做法 — Spring 把这套
 *       「资源持有者 + 引用计数 + finally 释放」抽象成<b>统一资源生命周期模板</b>。
 *       JdbcTemplate / HibernateTemplate / RedisTemplate / JmsTemplate 全部沿用。</li>
 *   <li>所以你以后做自家的"XxxTemplate"也应该长这样:
 *       {@code execute(Callback) { 拿资源 → try { callback } finally { release } } }</li>
 * </ul>
 *
 * <h4>断点指引</h4>
 * <ul>
 *   <li>{@code RedisConnectionUtils#releaseConnection}(~273 行)— 看 transactional / shared / pool 三分支</li>
 *   <li>{@code LettuceConnection#close} — 看 share-native 模式下的 nop close</li>
 *   <li>{@code LettucePoolingConnectionProvider#release}(~177 行)— 看池化模式的真正归还</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class CorrectFinallyReleaseDemo {

    public static void main(String[] args) {

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(L308RedisConfig.class)) {

            RedisConnectionFactory factory = context.getBean(RedisConnectionFactory.class);

            for (int i = 0; i < 5; i++) {
                System.out.println("🚦 round " + i + " : 借出 → 用 → finally 释放");
                manualGetAndRelease(factory);
            }

            System.out.println("\n✅ 5 次循环都正确释放,共享连接没有任何泄漏迹象。");
            System.out.println("   关键点:无论 try 内是否抛异常,finally 都必走。");
        }
    }

    private static void manualGetAndRelease(RedisConnectionFactory factory) {
        RedisConnection conn = RedisConnectionUtils.getConnection(factory);
        try {
            conn.ping();
        } finally {
            RedisConnectionUtils.releaseConnection(conn, factory);
        }
    }
}
