package org.springframework.data.redis.laboratory.l3_05.toushi;

/**
 * 模拟 Spring Data Redis 的 {@code LettuceConnection} 适配器。
 *
 * <p>它内部同时持有两个方向：
 * <ul>
 * <li>{@link MiniSharedConnection}：普通命令优先复用；</li>
 * <li>{@link MiniDedicatedConnection}：特殊场景第一次用到时懒加载。</li>
 * </ul>
 *
 * <p>这就是本章要偷师的关键设计：业务上层只看到一个 {@code RedisConnection} 风格接口，
 * 但内部根据 pipeline/transaction/blocking 等状态选择不同连接。</p>
 *
 * <pre>{@code
 * if (queueing || pipelined) {
 *     return dedicated;
 * }
 * return shared;
 * }</pre>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class MiniConnection {

	private final MiniSharedConnection sharedConnection;
	private final MiniConnectionProvider provider;

	private MiniDedicatedConnection dedicatedConnection;
	private boolean pipelined;
	private boolean queueing;
	private boolean closed;

	public MiniConnection(MiniSharedConnection sharedConnection, MiniConnectionProvider provider) {
		this.sharedConnection = sharedConnection;
		this.provider = provider;
		System.out.println("[wrapper] new MiniConnection wrapper " + System.identityHashCode(this));
	}

	public void get(String key) {
		send("GET " + key);
	}

	public void set(String key, String value) {
		send("SET " + key + " " + value);
	}

	public void openPipeline() {
		checkOpen();
		if (!pipelined) {
			pipelined = true;
			System.out.println("[wrapper] open pipeline -> dedicated lazy initialization");
			getOrCreateDedicatedConnection("pipeline").send("PIPELINE OPEN");
		}
	}

	public void closePipeline() {
		checkOpen();
		if (pipelined) {
			getOrCreateDedicatedConnection("pipeline").send("PIPELINE CLOSE");
			pipelined = false;
		}
	}

	public void multi() {
		checkOpen();
		if (!queueing) {
			queueing = true;
			getOrCreateDedicatedConnection("transaction").send("MULTI");
		}
	}

	public void exec() {
		checkOpen();
		if (!queueing) {
			throw new IllegalStateException("EXEC without MULTI");
		}
		getOrCreateDedicatedConnection("transaction").send("EXEC");
		queueing = false;
	}

	public void blpop(String key, int timeoutSeconds) {
		checkOpen();
		getOrCreateDedicatedConnection("blocking-command").send("BLPOP " + key + " " + timeoutSeconds);
	}

	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		System.out.println("[wrapper] close MiniConnection wrapper " + System.identityHashCode(this));
		if (dedicatedConnection != null) {
			provider.releaseDedicatedConnection(dedicatedConnection);
			dedicatedConnection = null;
		}
		sharedConnection.close();
	}

	private void send(String command) {
		checkOpen();
		if (queueing || pipelined) {
			getOrCreateDedicatedConnection(queueing ? "transaction" : "pipeline").send(command);
			return;
		}
		sharedConnection.send(command);
	}

	private MiniDedicatedConnection getOrCreateDedicatedConnection(String reason) {
		if (dedicatedConnection == null) {
			dedicatedConnection = provider.borrowDedicatedConnection(reason);
		}
		return dedicatedConnection;
	}

	private void checkOpen() {
		if (closed) {
			throw new IllegalStateException("MiniConnection wrapper already closed");
		}
	}
}
