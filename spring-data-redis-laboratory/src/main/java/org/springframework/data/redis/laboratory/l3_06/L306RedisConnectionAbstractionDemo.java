package org.springframework.data.redis.laboratory.l3_06;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.laboratory.l3_06.support.L306RedisConfig;

/**
 * <h3>L3-06 实验 3：业务层只面向 RedisConnection 抽象，不依赖 LettuceConnection</h3>
 *
 * <p>这个 demo 模拟一个最贴近生产的场景：</p>
 * <p><i>「我有一个 CacheService，它要做缓存读写，但我并不想让它知道
 * 底层是 Lettuce 还是 Jedis、是单机还是集群。」</i></p>
 *
 * <p>实现见 {@link AbstractionCacheService}：它只持有 {@link RedisConnectionFactory}，
 * 通过 {@code RedisConnectionUtils} 拿 {@code RedisConnection} 接口、用完释放。
 * <b>整个类不出现 LettuceXxx</b>。</p>
 *
 * <h4>「Spring 抽象的价值」</h4>
 * <ul>
 * <li>未来切换到 Jedis：只换 ConnectionFactory Bean，业务零修改；</li>
 * <li>接入监控：只在 ConnectionFactory 外面包一层 Proxy；</li>
 * <li>统一异常：业务只 catch {@code DataAccessException}；</li>
 * <li>面向接口编程：测试时可以用 mock 的 RedisConnectionFactory。</li>
 * </ul>
 *
 * <h4>断点建议</h4>
 * <ol>
 * <li>{@code RedisConnectionUtils.getConnection(RedisConnectionFactory)}</li>
 * <li>{@code LettuceConnectionFactory.getConnection()}（业务代码不需要 import 它）</li>
 * <li>{@code LettuceConnection.set(byte[], byte[])} —— 此时编译期变量类型是 RedisConnection，
 *     运行时却是 LettuceConnection，多态发生在这里。</li>
 * <li>{@code RedisConnectionUtils.releaseConnection(RedisConnection, RedisConnectionFactory)}</li>
 * </ol>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class L306RedisConnectionAbstractionDemo {

	public static void main(String[] args) {

		try (AnnotationConfigApplicationContext context =
				new AnnotationConfigApplicationContext(L306RedisConfig.class)) {

			RedisConnectionFactory factory = context.getBean(RedisConnectionFactory.class);
			AbstractionCacheService service = new AbstractionCacheService(factory);

			service.put("lab:l3_06:abs:flag", "on");
			System.out.println("GET = " + service.get("lab:l3_06:abs:flag"));

			System.out.println("\n注意：AbstractionCacheService 字段类型是 RedisConnectionFactory，");
			System.out.println("它根本看不到 LettuceConnectionFactory / LettuceConnection 的存在。");
			System.out.println("这就是「面向接口编程」+「工厂抽象」共同带来的解耦。");
		}
	}
}
