package org.springframework.data.redis.laboratory.l3_08.usage;

import java.util.List;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l3_08.support.L308RedisConfig;

/**
 * <h3>L3-08 / 🛠️ 使用阶段 · 实验 3:MULTI/EXEC 事务为什么必须独占连接?</h3>
 *
 * <p><b>核心约束</b>:MULTI 之后到 EXEC 之前的所有命令都被 Redis 服务端"暂存"在该连接的 transaction queue,
 * 直到 EXEC 才一次性原子执行。如果中途有别的线程往这条连接塞了 SET / GET,会被拼到事务里污染结果。
 * 因此事务期间<b>必须独占</b>一条连接。</p>
 *
 * <h4>SDR 实现路径(标准用法,需 PlatformTransactionManager)</h4>
 * <ol>
 *   <li>{@code template.setEnableTransactionSupport(true)} 打开后,业务调 {@code multi()} 时,SDR 会通过
 *       {@code RedisConnectionUtils#bindConnection} 把当前连接绑到 ThreadLocal
 *       (借助 Spring 的 {@code TransactionSynchronizationManager})。</li>
 *   <li>本线程后续所有命令都从 ThreadLocal 拿同一个 RedisConnection。</li>
 *   <li>事务提交/回滚时,通过事务同步钩子 {@code afterCompletion} 触发 unbind + close。</li>
 * </ol>
 *
 * <h4>本 Demo 的简化做法</h4>
 * <p>用 {@code RedisCallback} 在<b>同一个连接</b>内手动跑 MULTI/SET/EXEC,
 * 等价于事务期间独占一条连接的语义,Step Into 能看到 LettuceConnection 的内部状态切换。</p>
 *
 * <h4>断点指引</h4>
 * <ul>
 *   <li>{@code RedisConnectionUtils#doGetConnection}(~148 行)— 看 bind 分支</li>
 *   <li>{@code LettuceConnection#multi / exec}                   — 真正发 MULTI/EXEC</li>
 *   <li>{@code RedisConnectionUtils#unbindConnection}            — 事务结束清理 ThreadLocal</li>
 * </ul>
 *
 * <h4>古茗实战</h4>
 * <p>库存扣减用 Lua 脚本(原子)而不是 MULTI/EXEC,核心原因:
 * MULTI 期间连接被独占,高并发下池子撑不住。Lua 一次 RTT 完成,共享连接也安全。</p>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class DedicatedConnectionForTransactionDemo {

    public static void main(String[] args) {

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(L308RedisConfig.class)) {

            StringRedisTemplate template = context.getBean(StringRedisTemplate.class);

            System.out.println("🚦 STEP-1: 在同一个 RedisCallback 内手动 MULTI → SET ×3 → EXEC");

            List<Object> results = template.execute((RedisCallback<List<Object>>) connection -> {
                connection.multi();
                connection.stringCommands().set("lab:l3_08:tx:a".getBytes(), "1".getBytes());
                connection.stringCommands().set("lab:l3_08:tx:b".getBytes(), "2".getBytes());
                connection.stringCommands().set("lab:l3_08:tx:c".getBytes(), "3".getBytes());
                System.out.println("🚦 STEP-2: 三条命令已入 transaction queue,但还没 EXEC");
                return connection.exec();
            });

            System.out.println("🚦 STEP-3: exec 返回 " + results + " (3 个 OK)");
            System.out.println("\n💡 这条 callback 内的所有命令走的是「同一个 LettuceConnection 实例」,");
            System.out.println("   底层是 share-native 还是 dedicated,取决于 LettuceConnection 内部对 MULTI 的判定。");
            System.out.println("   生产环境强烈建议:不要在 share-native 模式跑 MULTI,改用 Lua 脚本。");
        }
    }
}
