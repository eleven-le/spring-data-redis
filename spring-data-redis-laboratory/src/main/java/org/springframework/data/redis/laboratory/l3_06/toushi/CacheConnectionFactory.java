package org.springframework.data.redis.laboratory.l3_06.toushi;

/**
 * <h3>L3-06 偷师：业务层唯一应该依赖的连接工厂抽象</h3>
 *
 * <p>类比 Spring Data Redis 的
 * {@link org.springframework.data.redis.connection.RedisConnectionFactory}：</p>
 * <ul>
 * <li>它屏蔽了「具体客户端如何创建连接」的所有细节；</li>
 * <li>它本身是一等公民 Bean，可以被 Spring 容器管理生命周期；</li>
 * <li>切换底层（Redis ↔ Tair）只需要换 Factory 实现，业务代码不动。</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-29
 */
public interface CacheConnectionFactory {

	CacheConnection getConnection();

	/** 类比 {@code DisposableBean.destroy()}，由容器统一关闭底层资源。 */
	void shutdown();
}
