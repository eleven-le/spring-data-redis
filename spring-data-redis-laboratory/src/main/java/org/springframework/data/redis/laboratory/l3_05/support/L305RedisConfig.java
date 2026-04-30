package org.springframework.data.redis.laboratory.l3_05.support;

import java.time.Duration;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.util.RedisConfigUtils;

/**
 * L3-05 实验公共配置：用最小 Spring Context 手动组装 Lettuce + RedisTemplate。
 *
 * <p>本章刻意不使用 Spring Boot，因为要让你直接看到：
 * <ul>
 * <li>{@link LettuceConnectionFactory} 是如何作为 {@code RedisConnectionFactory} 被 Spring 管理的；</li>
 * <li>{@code shareNativeConnection=true} 时，普通命令如何优先走共享 native connection；</li>
 * <li>pipeline、transaction、blocking command 等场景如何通过 provider 借出 dedicated connection；</li>
 * <li>这些对象在 Spring 容器中就是普通 {@code @Bean}，并不依赖 Boot 自动配置魔法。</li>
 * </ul>
 *
 * <p>断点建议：
 * <pre>{@code
 * LettuceConnectionFactory.afterPropertiesSet()
 * LettuceConnectionFactory.getConnection()
 * LettuceConnectionFactory.getSharedConnection()
 * LettuceConnection.<init>(StatefulConnection, LettuceConnectionProvider, long, int)
 * }</pre>
 *
 * <p>运行前准备：
 * <pre>{@code
 * docker run --name redis-lab -p 6379:6379 -d redis:7
 * }</pre>
 *
 * @author leilei
 * @since 2026-04-29
 */
@Configuration
public class L305RedisConfig {

	/**
	 * 创建非池化的 LettuceConnectionFactory。
	 *
	 * <p>关键配置是 {@link LettuceConnectionFactory#setShareNativeConnection(boolean)}：
	 * <ul>
	 * <li>{@code true}：普通 GET/SET/MGET/INCR 共享一条 Lettuce native connection；</li>
	 * <li>特殊语义命令仍然会通过 {@code LettuceConnectionProvider} 获取 dedicated connection；</li>
	 * <li>这个 factory 每次 {@code getConnection()} 都会 new 一个轻量 {@code LettuceConnection} wrapper，
	 * 但 wrapper 内部的 {@code asyncSharedConn} 指向同一条共享 native connection。</li>
	 * </ul>
	 *
	 * @return 手动创建的 LettuceConnectionFactory。
	 */
	@Bean(destroyMethod = "destroy")
	public LettuceConnectionFactory lettuceConnectionFactory() {

		LettuceConnectionFactory factory = new LettuceConnectionFactory(redisStandaloneConfiguration());
		factory.setShareNativeConnection(true);
		factory.setValidateConnection(false);
		return factory;
	}

	/**
	 * 创建 StringRedisTemplate。
	 *
	 * <p>本章所有 demo 都通过这个 template 进入源码链路：
	 * <pre>{@code
	 * RedisTemplate.execute(...)
	 * RedisConnectionUtils.getConnection(...)
	 * LettuceConnectionFactory.getConnection()
	 * LettuceConnection.getAsyncConnection()
	 * }</pre>
	 *
	 * @param connectionFactory 本章手动创建的连接工厂。
	 * @return StringRedisTemplate。
	 */
	@Bean
	public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
		return new StringRedisTemplate(connectionFactory);
	}

	/**
	 * 创建一个池化版 LettuceConnectionFactory，供你手动替换实验使用。
	 *
	 * <p>当前项目已经显式依赖 commons-pool2，所以这里给出可编译的池化配置。注意：在
	 * {@code shareNativeConnection=true} 下，连接池主要服务于 dedicated 路径，例如 pipeline、
	 * transaction、BLPOP、XREAD BLOCK、Pub/Sub 等；普通 GET/SET 默认不从池里 borrow。</p>
	 *
	 * <p>如果你想让 demo 使用池化版本，可以在 {@link #lettuceConnectionFactory()} 中改成：
	 * <pre>{@code
	 * return newPooledConnectionFactory();
	 * }</pre>
	 *
	 * @return 池化版 LettuceConnectionFactory。
	 */
	public static LettuceConnectionFactory newPooledConnectionFactory() {
		GenericObjectPoolConfig<Object> poolConfig = new GenericObjectPoolConfig<>();
		poolConfig.setMaxTotal(8);
		poolConfig.setMaxIdle(8);
		poolConfig.setMinIdle(1);
		poolConfig.setMaxWait(Duration.ofMillis(500));

		LettuceClientConfiguration clientConfiguration = LettucePoolingClientConfiguration.builder()
				.commandTimeout(Duration.ofSeconds(2))
				.poolConfig(poolConfig)
				.build();
		LettuceConnectionFactory factory = new LettuceConnectionFactory(redisStandaloneConfiguration(), clientConfiguration);
		factory.setShareNativeConnection(true);
		return factory;
	}

	private static RedisStandaloneConfiguration redisStandaloneConfiguration() {
		RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(RedisConfigUtils.getHost(), RedisConfigUtils.getPort());
		config.setDatabase(RedisConfigUtils.getDatabase());

		String password = RedisConfigUtils.getPassword();
		if (password != null && !password.isEmpty()) {
			config.setPassword(RedisPassword.of(password));
		}
		return config;
	}
}
