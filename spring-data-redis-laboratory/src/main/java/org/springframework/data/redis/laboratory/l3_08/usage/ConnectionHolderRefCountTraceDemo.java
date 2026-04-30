package org.springframework.data.redis.laboratory.l3_08.usage;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisConnectionUtils;
import org.springframework.data.redis.laboratory.l3_08.support.L308RedisConfig;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * <h3>L3-08 / 🛠️ 使用阶段 · 实验 4:ConnectionHolder 引用计数追踪</h3>
 *
 * <p>{@code RedisConnectionUtils$RedisConnectionHolder} 继承 Spring 的
 * {@link org.springframework.transaction.support.ResourceHolderSupport},
 * 它的祖传字段 {@code referenceCount} 就是引用计数器:</p>
 *
 * <ul>
 *   <li>每次 {@code RedisConnectionUtils#getConnection(factory)} → {@code requested()} → ref++</li>
 *   <li>每次 {@code RedisConnectionUtils#releaseConnection(conn, factory)} → {@code released()} → ref--</li>
 *   <li><b>只有 ref 减到 0,才真正调 {@code conn.close()} 归还到底层池/共享连接</b></li>
 * </ul>
 *
 * <h4>为什么要引用计数?</h4>
 * <p>Spring 事务嵌套场景:外层方法 getConnection → 调内层方法又 getConnection →
 * 内层方法 finally release → 此时<b>外层还在用!不能真关</b>。引用计数就是为这种嵌套设计的。
 * (DataSource、HibernateSession、JmsConnection 都用同一个套路,Spring 的「资源持有者模板」🥷)</p>
 *
 * <h4>断点指引</h4>
 * <ul>
 *   <li>{@code RedisConnectionUtils#doGetConnection}(~148 行)— bind 分支会创建 ConnectionHolder</li>
 *   <li>{@code ResourceHolderSupport#requested} / {@code #released} — ref±±</li>
 *   <li>{@code RedisConnectionUtils#releaseConnection}(~273 行)— 看 ref==0 才 close 的判定</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class ConnectionHolderRefCountTraceDemo {

    public static void main(String[] args) {

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(L308RedisConfig.class)) {

            RedisConnectionFactory factory = context.getBean(RedisConnectionFactory.class);

            System.out.println("🚦 STEP-1: 模拟事务,bind=true 路径会创建 ConnectionHolder 并 ref=1");
            RedisConnection outer = RedisConnectionUtils.bindConnection(factory);

            System.out.println("           当前 ThreadLocal 已绑定: "
                    + TransactionSynchronizationManager.hasResource(factory));

            System.out.println("🚦 STEP-2: 嵌套调用 getConnection → 复用同一个 holder, ref++");
            RedisConnection inner = RedisConnectionUtils.getConnection(factory);
            System.out.println("           outer == inner ? " + (outer == inner)
                    + " (Spring 会复用 ThreadLocal 里的同一条)");

            System.out.println("🚦 STEP-3: 内层 release → ref--,此时 ref=1 还不会真关");
            RedisConnectionUtils.releaseConnection(inner, factory);
            System.out.println("           ThreadLocal 仍绑定: "
                    + TransactionSynchronizationManager.hasResource(factory));

            System.out.println("🚦 STEP-4: 外层 unbind → ref--, ref=0 → 真关 + 解绑 ThreadLocal");
            RedisConnectionUtils.unbindConnection(factory);
            System.out.println("           ThreadLocal 已解绑: "
                    + !TransactionSynchronizationManager.hasResource(factory));
        }
    }
}
