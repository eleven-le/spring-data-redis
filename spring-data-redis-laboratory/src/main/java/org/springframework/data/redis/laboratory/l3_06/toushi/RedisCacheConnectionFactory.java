package org.springframework.data.redis.laboratory.l3_06.toushi;

/**
 * <h3>L3-06 偷师：CacheConnectionFactory 的 Redis 实现</h3>
 *
 * <p>对应 {@code LettuceConnectionFactory}：</p>
 * <ul>
 * <li>持有一个底层「真实客户端」{@link NativeRedisClient}；</li>
 * <li>每次 {@code getConnection()} 返回一个 wrapper（适配器实例），不重复建客户端；</li>
 * <li>shutdown 统一关闭底层资源。</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class RedisCacheConnectionFactory implements CacheConnectionFactory {

	private final NativeRedisClient nativeClient;

	public RedisCacheConnectionFactory() {
		this.nativeClient = new NativeRedisClient();
		this.nativeClient.connect();
	}

	@Override
	public CacheConnection getConnection() {
		// 类比 LettuceConnectionFactory.getConnection 每次 new 一个轻量 wrapper
		return new RedisCacheConnectionAdapter(nativeClient);
	}

	@Override
	public void shutdown() {
		nativeClient.shutdown();
	}
}
