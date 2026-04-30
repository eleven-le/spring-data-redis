package org.springframework.data.redis.laboratory.l3_05.toushi;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模拟 {@code LettuceConnectionProvider}。
 *
 * <p>它解决的问题是：业务适配层 {@link MiniConnection} 不应该知道连接到底来自哪里。
 * 在真实源码中，provider 背后可能是：
 * <ul>
 * <li>普通 standalone provider：每次创建真实连接；</li>
 * <li>pooling provider：从 Commons Pool borrow；</li>
 * <li>cluster provider：按节点/连接类型路由；</li>
 * <li>exception translating provider：把 Lettuce 异常翻译成 Spring DataAccessException。</li>
 * </ul>
 *
 * <p>这里用一个简单计数器模拟 lazy shared connection 和 dedicated borrow/release。</p>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class MiniConnectionProvider {

	private final AtomicInteger ids = new AtomicInteger();
	private MiniSharedConnection sharedConnection;

	public MiniSharedConnection getSharedConnection() {
		if (sharedConnection == null) {
			System.out.println("[provider] lazy create shared connection");
			sharedConnection = new MiniSharedConnection(ids.incrementAndGet());
		}
		return sharedConnection;
	}

	public MiniDedicatedConnection borrowDedicatedConnection(String reason) {
		return new MiniDedicatedConnection(ids.incrementAndGet(), reason);
	}

	public void releaseDedicatedConnection(MiniDedicatedConnection connection) {
		connection.close();
	}
}
