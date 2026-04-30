package org.springframework.data.redis.laboratory.l3_06.toushi;

/**
 * <h3>L3-06 偷师：业务层唯一应该依赖的 KV 抽象</h3>
 *
 * <p>把它类比成 Spring Data Redis 里的
 * {@link org.springframework.data.redis.connection.RedisConnection}：</p>
 * <ul>
 * <li>方法签名只描述「业务语义」（get / set / del），不暴露任何底层客户端类型；</li>
 * <li>底层是 Redis、Tair、Memcached、还是公司自研 KV，业务全都不用关心；</li>
 * <li>未来切换底层实现，业务代码零修改。</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-29
 */
public interface CacheConnection extends AutoCloseable {

	void set(String key, String value);

	String get(String key);

	void del(String key);

	@Override
	void close();
}
