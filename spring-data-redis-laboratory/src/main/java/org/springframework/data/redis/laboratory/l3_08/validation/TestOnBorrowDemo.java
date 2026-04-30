package org.springframework.data.redis.laboratory.l3_08.validation;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l3_08.support.L308PooledRedisConfig;

/**
 * <h3>L3-08 / ✅ 验证阶段 · 实验 1:testOnBorrow 的真实代价</h3>
 *
 * <p>{@code GenericObjectPoolConfig} 有<b>四大验证开关</b>:</p>
 * <table>
 *   <tr><th>开关</th><th>触发时机</th><th>性能影响</th></tr>
 *   <tr><td>{@code testOnCreate}</td><td>对象首次创建后</td><td>低,只跑一次</td></tr>
 *   <tr><td>{@code testOnBorrow}</td><td>每次 borrowObject 前</td><td><b>高</b>,N 倍 PING</td></tr>
 *   <tr><td>{@code testOnReturn}</td><td>returnObject 前</td><td>高</td></tr>
 *   <tr><td>{@code testWhileIdle}</td><td>Evictor 后台线程巡检空闲对象</td><td>低,与业务异步</td></tr>
 * </table>
 *
 * <h4>SDR 内部实现</h4>
 * <p>{@link org.springframework.data.redis.connection.lettuce.LettucePoolingConnectionProvider}
 * 用 {@code io.lettuce.core.support.ConnectionPoolSupport.createGenericObjectPool} 创建池,
 * 内部 {@code RedisPooledObjectFactory#validateObject} 调用 {@code connection.sync().ping()}。
 * <b>所以"验证"的本质就是发一次 PING 命令</b>。</p>
 *
 * <h4>断点指引</h4>
 * <ul>
 *   <li>{@code GenericObjectPool#borrowObject(long)} — 看 {@code if (testOnBorrow) ...} 分支</li>
 *   <li>{@code ConnectionPoolSupport$RedisPooledObjectFactory#validateObject}(Lettuce 包内) — PING 现场</li>
 *   <li>{@code LettucePoolingConnectionProvider#getConnection}(SDR ~94 行) — 池化分流入口</li>
 * </ul>
 *
 * <h4>古茗 C 端决策</h4>
 * <p>商品详情 QPS 5w,每次 borrow 多一次 PING = 多 5w 次额外命令 + 额外 RTT。
 * <b>结论</b>:走 {@code shareNativeConnection} 时根本用不到池化;
 * 强制池化场景(MULTI/Pub-Sub)<b>关掉 testOnBorrow,用 testWhileIdle 兜底</b>。</p>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class TestOnBorrowDemo {

    public static void main(String[] args) {

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(L308PooledRedisConfig.class)) {

            StringRedisTemplate template = context.getBean("pooledStringRedisTemplate", StringRedisTemplate.class);

            System.out.println("🚦 STEP-1: 池化 + testOnBorrow=true,即将连发 1000 次 SET");
            System.out.println("           每一次 borrow 都会触发一次 PING(由 Lettuce ConnectionPoolSupport 实现)");

            // 强制走池化路径(execute 内部:shareNativeConnection=false → borrow from pool)
            long start = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                template.opsForValue().set("lab:l3_08:val:" + i, "v");
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            System.out.println("🚦 STEP-2: 1000 次池化 SET 总耗时 " + elapsed + " ms"
                    + "(粗略 = 1000 × (PING + SET))");

            System.out.println("\n💡 对照:把 testOnBorrow 关掉再跑一次,通常能快 30%~50%。");
            System.out.println("   (改 L308PooledRedisConfig 中 setTestOnBorrow(false) 重跑)");
        }
    }
}
