package org.springframework.data.redis.laboratory.l3_01;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.laboratory.util.RedisConfigUtils;

/**
 * L3-01 反面教材：故意不释放连接，观察共享模式下的"无痛"和池化模式下的"雪崩"
 * <p>
 * 实验目的：理解为什么 Lettuce 共享模式比 Jedis 对"忘记 close"更宽容，
 * 但这不是你省略 close 的理由。
 */
public class L301_LeakDemoLab {

    public static void main(String[] args) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(RedisConfigUtils.getHost(), RedisConfigUtils.getPort());
        config.setPassword(RedisConfigUtils.getPassword());
        JedisConnectionFactory factory = new JedisConnectionFactory(config);
        factory.afterPropertiesSet();

        System.out.println("[LeakDemo] === Lettuce 共享模式：故意泄漏 100 个连接 ===");

        for (int i = 0; i < 100; i++) {
            // 故意不 close —— Lettuce 共享模式下不会爆（但这是坏习惯！）
            RedisConnection conn = factory.getConnection();
            conn.ping();
            System.out.println("获取到第+"+i+"+次链接");
            // 没有 conn.close()!
        }

        System.out.println("[LeakDemo] 100 次 getConnection() + ping 完成，没有 close。");
        System.out.println("[LeakDemo] Lettuce 共享模式下没有爆——因为 100 个 LettuceConnection 包装的是同一条物理连接。");
        System.out.println("[LeakDemo] 但切到 Jedis 或 Lettuce 池化模式，第 9 次就会超时阻塞！");
        System.out.println("[LeakDemo] 结论：永远在 finally 里 close，不要赌运气。");

        factory.destroy();
    }
}
