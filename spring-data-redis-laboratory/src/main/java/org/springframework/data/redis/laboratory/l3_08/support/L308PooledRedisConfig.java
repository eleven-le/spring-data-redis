package org.springframework.data.redis.laboratory.l3_08.support;

import java.time.Duration;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * <h3>L3-08 池化版配置(用于验证 / 释放 / 驱逐阶段)</h3>
 *
 * <p>这一份配置开启了 Apache Commons Pool 2 的 {@code GenericObjectPool},
 * 是观察以下源码点的关键入口:</p>
 *
 * <ul>
 *   <li>{@code testOnBorrow=true} → {@code GenericObjectPool#borrowObject} 内会回调
 *       {@code LettucePoolingConnectionProvider#validateObject} → 真的发一次 {@code PING};</li>
 *   <li>{@code testWhileIdle=true} + {@code timeBetweenEvictionRuns} 启动后台
 *       {@code BaseGenericObjectPool$Evictor} 守护线程;</li>
 *   <li>{@code maxTotal=2} 故意设小,5 秒内能复现「泄漏 → 借不到 → 抛 NoSuchElementException」;</li>
 *   <li>{@code shareNativeConnection=false} — 强制走池化路径,
 *       否则 {@link LettuceConnectionFactory#getConnection()} 会优先返回共享连接,池子用不上。</li>
 * </ul>
 *
 * <h4>典型断点</h4>
 * <ol>
 *   <li>{@code LettucePoolingConnectionProvider#getConnection} — 池化 Provider 借连接入口</li>
 *   <li>{@code GenericObjectPool#borrowObject} — Apache Pool 借出 + 验证</li>
 *   <li>{@code LettucePoolingConnectionProvider#validateObject} — 真正执行 PING 的位置</li>
 *   <li>{@code BaseGenericObjectPool$Evictor#run} — Evictor 守护线程定时扫描</li>
 * </ol>
 *
 * @author leilei
 * @since 2026-04-30
 */
@Configuration
public class L308PooledRedisConfig {

    /**
     * 池化 Factory。{@code shareNativeConnection=false} 是关键:不关闭它,
     * 命令永远走共享 Channel,看不到池化的 borrow/return。
     */
    @Bean(destroyMethod = "destroy")
    public LettuceConnectionFactory pooledLettuceConnectionFactory() {

        GenericObjectPoolConfig<Object> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(2);                                  // 故意小,便于 Demo 复现耗尽
        poolConfig.setMaxIdle(2);
        poolConfig.setMinIdle(0);
        poolConfig.setMaxWait(Duration.ofSeconds(2));               // 借不到 → 2 秒后抛异常
        poolConfig.setTestOnBorrow(true);                           // 借时 PING
        poolConfig.setTestWhileIdle(true);                          // 空闲时由 Evictor 巡检
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(3));
        poolConfig.setMinEvictableIdleTime(Duration.ofSeconds(5));

        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .poolConfig(poolConfig)
                .commandTimeout(Duration.ofSeconds(2))
                .shutdownTimeout(Duration.ofMillis(200))            // Demo 加速关闭
                .shutdownQuietPeriod(Duration.ofMillis(50))
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                L308RedisConfig.redisStandaloneConfiguration(), clientConfig);
        factory.setShareNativeConnection(false);                    // 关键:强制走池
        factory.setValidateConnection(false);
        return factory;
    }

    @Bean
    public StringRedisTemplate pooledStringRedisTemplate(LettuceConnectionFactory pooledLettuceConnectionFactory) {
        return new StringRedisTemplate(pooledLettuceConnectionFactory);
    }
}
