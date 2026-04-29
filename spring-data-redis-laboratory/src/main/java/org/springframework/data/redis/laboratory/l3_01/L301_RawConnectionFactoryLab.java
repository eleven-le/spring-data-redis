package org.springframework.data.redis.laboratory.l3_01;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.laboratory.util.RedisConfigUtils;

import java.util.concurrent.TimeUnit;

/**
 * L3-01 纯原生实验：手动操控 LettuceConnectionFactory 全生命周期
 * <p>
 * 运行前提：本地 Redis 已启动 → docker run -d -p 6379:6379 redis:7
 * <p>
 * 实验目的：
 * 1. 理解 afterPropertiesSet() 的不可或缺性（注释掉看报错）
 * 2. 理解 destroy() 的必要性（注释掉看 JVM 不退出）
 * 3. 理解 finally 释放连接的铁律
 */
public class L301_RawConnectionFactoryLab {

    public static void main(String[] args) {

        // ═══════════════════════════════════════════════
        // 第一步：手动创建 Redis 配置（对标 application.yml 里的 spring.redis.*）
        // ═══════════════════════════════════════════════
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(RedisConfigUtils.getHost());
        config.setPort(RedisConfigUtils.getPort());
        config.setDatabase(RedisConfigUtils.getDatabase());
        config.setPassword(RedisConfigUtils.getPassword());
        // 如果有密码: config.setPassword(RedisPassword.of("your-password"));

        // ═══════════════════════════════════════════════
        // 第二步：手动创建工厂（Spring Boot 下由 RedisAutoConfiguration 自动完成）
        // ═══════════════════════════════════════════════
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);

        // ═══════════════════════════════════════════════
        // 第三步：【核心】显式调用生命周期初始化
        // ═══════════════════════════════════════════════
        // 如果注释掉下面这行，getConnection() 会立即抛出：
        // java.lang.IllegalStateException:
        //   "LettuceConnectionFactory was not initialized through afterPropertiesSet()"
        //
        // afterPropertiesSet() 内部干了什么？（源码 LettuceConnectionFactory.java:336）
        //   1. createClient()         → 创建底层 Lettuce RedisClient（启动 Netty EventLoop）
        //   2. createConnectionProvider() → 创建连接提供者 + 异常翻译装饰器
        //   3. this.initialized = true    → 打开守卫开关
        factory.afterPropertiesSet();
        System.out.println("[Lab] LettuceConnectionFactory initialized.");

        // ═══════════════════════════════════════════════
        // 第四步：获取连接 + 执行命令 + 安全释放
        // ═══════════════════════════════════════════════
        RedisConnection connection = null;
        try {
            connection = factory.getConnection();

            // PING —— 最经典的连通性验证
            String pong = connection.ping();
            System.out.println("[Lab] PING → " + pong);

            // 写入一个 key
            connection.stringCommands().set("lab:l3-01:hello".getBytes(), "world".getBytes(), Expiration.from(60L, TimeUnit.SECONDS), RedisStringCommands.SetOption.ifAbsent());
            System.out.println("[Lab] SET lab:l3-01:hello world → OK");

            // 读回来
            byte[] value = connection.stringCommands().get("lab:l3-01:hello".getBytes());
            System.out.println("[Lab] GET lab:l3-01:hello → " + new String(value));

            // 验证：getNativeConnection() 拿到的是 Lettuce 的 StatefulRedisConnection
            Object nativeConn = connection.getNativeConnection();
            System.out.println("[Lab] Native connection type: " + nativeConn.getClass().getSimpleName());

        } finally {
            // ═══════════════════════════════════════════
            // 第五步：【高并发铁律】finally 中释放连接
            // ═══════════════════════════════════════════
            if (connection != null) {
                try {
                    connection.close();
                    System.out.println("[Lab] Connection released.");
                } catch (Exception e) {
                    System.err.println("[Lab] 释放连接异常: " + e.getMessage());
                }
            }
        }

        // ═══════════════════════════════════════════════
        // 第六步：销毁工厂，释放 Netty EventLoop 线程和底层资源
        // ═══════════════════════════════════════════════
        // 如果不调 destroy()，JVM 进程不会退出（Netty 的非 daemon 线程会 hold 住）
        factory.destroy();
        System.out.println("[Lab] Factory destroyed. Bye!");
    }
}
