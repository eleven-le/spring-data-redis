package org.springframework.data.redis.laboratory.l3_06.toushi;

/**
 * <h3>L3-06 偷师：业务层只面向 Cache 抽象，不感知底层客户端</h3>
 *
 * <p>这是「偷师」最关键的一类——业务团队代码。注意：</p>
 * <ul>
 * <li>它的字段类型只到 {@link CacheConnectionFactory} 接口；</li>
 * <li>它<b>从不 import {@code NativeRedisClient}</b>；</li>
 * <li>切换底层只需要替换 Factory 的实现，业务零修改。</li>
 * </ul>
 *
 * <p>对应 Spring Data Redis 的现实：业务用 {@code RedisTemplate}，
 * 它内部只看到 {@code RedisConnectionFactory}，从来不 import {@code LettuceConnection}。</p>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class BusinessCacheService {

	private final CacheConnectionFactory connectionFactory;

	public BusinessCacheService(CacheConnectionFactory connectionFactory) {
		this.connectionFactory = connectionFactory;
	}

	public void cacheUser(String userId, String json) {
		try (CacheConnection connection = connectionFactory.getConnection()) {
			connection.set("user:" + userId, json);
		}
	}

	public String loadUser(String userId) {
		try (CacheConnection connection = connectionFactory.getConnection()) {
			return connection.get("user:" + userId);
		}
	}
}
