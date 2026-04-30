package org.springframework.data.redis.laboratory.l3_05.toushi;

/**
 * 模拟 Lettuce 的 shared native connection。
 *
 * <p>真实 Spring Data Redis 中，普通 GET/SET 最终会走：
 * <pre>{@code
 * LettuceConnection.getAsyncConnection()
 *   -> asyncSharedConn.async()
 * }</pre>
 *
 * <p>这个类不连接 Redis，只打印日志。它代表一条可以被多个业务线程复用的非阻塞连接：
 * <ul>
 * <li>普通短命令适合共享；</li>
 * <li>它不应该承载事务、pipeline、blocking command、pub/sub 这类连接级语义；</li>
 * <li>{@link #close()} 不会真的关闭共享连接，因为共享连接生命周期归 factory/provider 管。</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class MiniSharedConnection {

	private final int id;

	public MiniSharedConnection(int id) {
		this.id = id;
		System.out.println("[shared] create native shared connection #" + id);
	}

	public void send(String command) {
		System.out.println("[shared-" + id + "] " + command);
	}

	public void close() {
		System.out.println("[shared-" + id + "] close ignored by wrapper; factory owns shared lifecycle");
	}
}
