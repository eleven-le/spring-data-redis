package org.springframework.data.redis.laboratory.l3_05.toushi;

/**
 * 模拟 {@code RedisTemplate} 的 Template Method + Callback。
 *
 * <p>Template 的价值是把固定流程收口：
 * <ol>
 * <li>获取连接；</li>
 * <li>必要时绑定 ThreadLocal holder；</li>
 * <li>执行用户 callback；</li>
 * <li>finally 中释放连接。</li>
 * </ol>
 *
 * <p>业务代码只写 callback，不用每次手写 try/finally。框架代码则可以稳定地管理 shared/dedicated
 * 连接生命周期。</p>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class MiniRedisTemplate {

	private final MiniConnectionFactory connectionFactory;

	public MiniRedisTemplate(MiniConnectionFactory connectionFactory) {
		this.connectionFactory = connectionFactory;
	}

	public <T> T execute(MiniRedisCallback<T> callback) {

		MiniConnectionHolder holder = MiniTransactionSynchronizationManager.getResource(connectionFactory);
		boolean existingConnection = holder != null;

		MiniConnection connection;
		if (existingConnection) {
			holder.requested();
			connection = holder.getConnection();
			System.out.println("[template] reuse thread-bound connection wrapper");
		}
		else {
			connection = connectionFactory.getConnection();
		}

		try {
			return callback.doInRedis(connection);
		}
		finally {
			if (existingConnection) {
				holder.released();
			}
			else {
				connection.close();
			}
		}
	}

	public void executePipelined(MiniRedisCallback<Void> callback) {
		execute(connection -> {
			connection.openPipeline();
			try {
				callback.doInRedis(connection);
			}
			finally {
				connection.closePipeline();
			}
			return null;
		});
	}

	public <T> T executeSession(MiniRedisCallback<T> callback) {

		MiniConnection connection = connectionFactory.getConnection();
		MiniConnectionHolder holder = new MiniConnectionHolder(connection);
		holder.requested();
		MiniTransactionSynchronizationManager.bindResource(connectionFactory, holder);
		try {
			return callback.doInRedis(connection);
		}
		finally {
			holder.released();
			MiniConnectionHolder unbound = MiniTransactionSynchronizationManager.unbindResource(connectionFactory);
			if (unbound != null) {
				unbound.getConnection().close();
			}
		}
	}

	/**
	 * 用户业务回调。
	 *
	 * <p>真实 RedisTemplate 中对应 {@code RedisCallback<T>} 和 {@code SessionCallback<T>}。
	 * 框架负责资源管理，业务只负责表达“我要在连接上做什么”。</p>
	 *
	 * @param <T> 回调返回值类型。
	 */
	@FunctionalInterface
	public interface MiniRedisCallback<T> {

		T doInRedis(MiniConnection connection);
	}
}
