package org.springframework.data.redis.laboratory.l3_05.toushi;

/**
 * 模拟 {@code RedisConnectionFactory / LettuceConnectionFactory}。
 *
 * <p>Factory 的存在意义不是“每次都创建物理连接”，而是把底层连接策略封装起来，
 * 每次交给上层一个新的轻量 wrapper：</p>
 *
 * <pre>{@code
 * MiniConnection wrapper = new MiniConnection(sharedConnection, provider);
 * }</pre>
 *
 * <p>这个设计可以迁移到业务系统：例如第三方风控 API、支付渠道 client、搜索集群 client，
 * 上层只依赖 factory，底层可以从共享连接切换到池化连接，而业务代码不用改。</p>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class MiniConnectionFactory {

	private final MiniConnectionProvider provider = new MiniConnectionProvider();

	public MiniConnection getConnection() {
		return new MiniConnection(provider.getSharedConnection(), provider);
	}
}
