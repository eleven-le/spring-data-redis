package org.springframework.data.redis.laboratory.l3_08.creation;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.laboratory.l3_08.support.L308RedisConfig;

/**
 * <h3>L3-08 / 🐣 创建阶段 · 实验 2:afterPropertiesSet 内部到底干了什么?</h3>
 *
 * <p>{@link LettuceConnectionFactory#afterPropertiesSet()} 的核心动作(对照 SDR 2.7.18 源码 ~359 行):</p>
 * <ol>
 *   <li>构建 {@code ClientResources}(Lettuce 全局资源,默认包含
 *       Netty {@code EventLoopGroup} + {@code DefaultEventExecutor})</li>
 *   <li>由 {@code createClient()} 工厂方法构建 {@code AbstractRedisClient}
 *       (单机/哨兵/集群三态),核心入口是 {@code RedisClient.create(...)} </li>
 *   <li>构建 {@link org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider}
 *       (默认 {@code StandaloneConnectionProvider},如果配了 pool 则替换为
 *       {@code LettucePoolingConnectionProvider})</li>
 *   <li>构建异常转换器 {@code LettuceExceptionConverter}</li>
 *   <li>{@code initialized = true},如果 {@code eagerInitialization=true} 则触发
 *       {@code initConnection()} 直接预热共享连接</li>
 * </ol>
 *
 * <h4>偷师 Spring 扩展点</h4>
 * <p>Spring {@link org.springframework.beans.factory.InitializingBean} 是「资源初始化模板方法」的经典体现:</p>
 * <ul>
 *   <li>容器收集所有 Bean → 依赖注入完成 → <b>对每个实现 {@code InitializingBean} 的 Bean
 *       调用 {@code afterPropertiesSet}</b> → 进入 {@code BeanPostProcessor#postProcessAfterInitialization}</li>
 *   <li>它的兄弟接口是 {@link org.springframework.beans.factory.DisposableBean}({@code destroy()}),
 *       一前一后构成 Bean 生命周期的"出生证"和"死亡证"</li>
 * </ul>
 *
 * <h4>断点指引</h4>
 * <ul>
 *   <li>{@code LettuceConnectionFactory#afterPropertiesSet} — 整个方法体逐行 F8</li>
 *   <li>{@code LettuceConnectionFactory#createClient} — 看单机/哨兵/集群分流</li>
 *   <li>{@code LettuceConnectionFactory#doCreateConnectionProvider} — 看池化分流</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class ConnectionFactoryAfterPropertiesSetTrace {

    public static void main(String[] args) {

        System.out.println("🚦 STEP-1: new AnnotationConfigApplicationContext —— 即将走 Spring Bean 生命周期");

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(L308RedisConfig.class)) {

            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);

            System.out.println("🚦 STEP-2: afterPropertiesSet 已完成,以下断言成立:");
            System.out.println("           - factoryClass:           " + factory.getClass().getSimpleName());
            System.out.println("           - shareNativeConnection:  " + factory.getShareNativeConnection());
            System.out.println("           - database:               " + factory.getDatabase());

            System.out.println("🚦 STEP-3: 主动 initConnection() 预热共享连接(等价于 eagerInitialization=true)");
            factory.initConnection();

            System.out.println("🚦 STEP-4: 此后 getConnection() 不会再触发 lazy 初始化,微秒级返回");
            long start = System.nanoTime();
            factory.getConnection().close();
            System.out.println("           getConnection 耗时 " + (System.nanoTime() - start) / 1000 + " μs");
        }
    }
}
