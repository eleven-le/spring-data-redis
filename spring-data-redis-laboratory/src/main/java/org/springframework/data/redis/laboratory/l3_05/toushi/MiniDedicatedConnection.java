package org.springframework.data.redis.laboratory.l3_05.toushi;

/**
 * 模拟专用连接。
 *
 * <p>真实 {@code asyncDedicatedConn} 的生命周期是：
 * <pre>{@code
 * first special command
 *   -> LettuceConnection.getOrCreateDedicatedConnection()
 *   -> connectionProvider.getConnection(StatefulConnection.class)
 * close wrapper
 *   -> connectionProvider.release(asyncDedicatedConn)
 * }</pre>
 *
 * <p>这个类代表一条被某个 wrapper 独占的连接。它可以被 pipeline/transaction/blocking command
 * 使用，因为这些场景需要清晰的连接上下文边界。</p>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class MiniDedicatedConnection {

	private final int id;
	private final String reason;
	private boolean closed;

	public MiniDedicatedConnection(int id, String reason) {
		this.id = id;
		this.reason = reason;
		System.out.println("[dedicated] borrow connection #" + id + " reason=" + reason);
	}

	public void send(String command) {
		if (closed) {
			throw new IllegalStateException("dedicated connection #" + id + " already closed");
		}
		System.out.println("[dedicated-" + id + "] " + command);
	}

	public int getId() {
		return id;
	}

	public String getReason() {
		return reason;
	}

	public void close() {
		closed = true;
		System.out.println("[dedicated] release connection #" + id + " reason=" + reason);
	}
}
