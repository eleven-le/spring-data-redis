/*
 * Copyright 2017-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.redis.connection.lettuce;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.support.AsyncConnectionPoolSupport;
import io.lettuce.core.support.AsyncPool;
import io.lettuce.core.support.BoundedPoolConfig;
import io.lettuce.core.support.CommonsPool2ConfigConverter;
import io.lettuce.core.support.ConnectionPoolSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.PoolException;
import org.springframework.util.Assert;

/**
 * {@link LettuceConnectionProvider} with connection pooling support. This connection provider holds multiple pools (one
 * per connection type and allocation type (synchronous/asynchronous)) for contextualized connection allocation.
 * <p>
 * Each allocated connection is tracked and to be returned into the pool which created the connection. Instances of this
 * class require {@link #destroy() disposal} to de-allocate lingering connections that were not returned to the pool and
 * to close the pools.
 * <p>
 * This provider maintains separate pools due to the allocation nature (synchronous/asynchronous). Asynchronous
 * connection pooling requires a non-blocking allocation API. Connections requested asynchronously can be returned
 * synchronously and vice versa. A connection obtained synchronously is returned to the synchronous pool even if
 * {@link #releaseAsync(StatefulConnection) released asynchronously}. This is an undesired case as the synchronous pool
 * will block the asynchronous flow for the time of release.
 *
 * @author Mark Paluch
 * @author Christoph Strobl
 * @since 2.0
 * @see #getConnection(Class)
 */
class LettucePoolingConnectionProvider implements LettuceConnectionProvider, RedisClientProvider, DisposableBean {

	private final static Log log = LogFactory.getLog(LettucePoolingConnectionProvider.class);

	private final LettuceConnectionProvider connectionProvider;
	private final GenericObjectPoolConfig poolConfig;
	private final Map<StatefulConnection<?, ?>, GenericObjectPool<StatefulConnection<?, ?>>> poolRef = new ConcurrentHashMap<>(
			32);

	private final Map<StatefulConnection<?, ?>, AsyncPool<StatefulConnection<?, ?>>> asyncPoolRef = new ConcurrentHashMap<>(
			32);
	private final Map<CompletableFuture<StatefulConnection<?, ?>>, AsyncPool<StatefulConnection<?, ?>>> inProgressAsyncPoolRef = new ConcurrentHashMap<>(
			32);
	private final Map<Class<?>, GenericObjectPool<StatefulConnection<?, ?>>> pools = new ConcurrentHashMap<>(32);
	private final Map<Class<?>, AsyncPool<StatefulConnection<?, ?>>> asyncPools = new ConcurrentHashMap<>(32);
	private final BoundedPoolConfig asyncPoolConfig;

	LettucePoolingConnectionProvider(LettuceConnectionProvider connectionProvider,
			LettucePoolingClientConfiguration clientConfiguration) {

		Assert.notNull(connectionProvider, "ConnectionProvider must not be null!");
		Assert.notNull(clientConfiguration, "ClientConfiguration must not be null!");

		this.connectionProvider = connectionProvider;
		this.poolConfig = clientConfiguration.getPoolConfig();
		this.asyncPoolConfig = CommonsPool2ConfigConverter.bounded(this.poolConfig);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider#getConnection(java.lang.Class)
	 */
	@Override
	public <T extends StatefulConnection<?, ?>> T getConnection(Class<T> connectionType) {

		/* ===== L3-05 讲解注释 BY 军火库 =====
		 * <h3>🔥 池化策略 · dedicated 连接 borrow 点</h3>
		 *
		 * <p><b>调用方</b>：{@code LettuceConnection#doGetAsyncDedicatedConnection()}，
		 * 以及 Pub/Sub、Sentinel、Cluster 节点级连接等需要 Provider 获取连接的路径。</p>
		 *
		 * <p><b>触发条件</b>：启用 Lettuce pooling client configuration 后，
		 * 事务、BLPOP、XREAD BLOCK 等 dedicated 路径不再每次新建连接，而是从对应类型的
		 * {@code GenericObjectPool} borrow。</p>
		 *
		 * <p><b>做出的决定</b>：按 {@code connectionType} 维护不同连接池，并把借出的连接记录到
		 * {@code poolRef}，为后续 {@code release(connection)} 找到正确的归还池。</p>
		 *
		 * <p><b>跳过它会怎样</b>：专用连接没有资源边界，阻塞队列消费或长事务高峰会无限创建连接；
		 * Redis Server 连接数和客户端 FD 会快速升高。</p>
		 * ===== END ===== */
		GenericObjectPool<StatefulConnection<?, ?>> pool = pools.computeIfAbsent(connectionType, poolType -> {
			return ConnectionPoolSupport.createGenericObjectPool(() -> connectionProvider.getConnection(connectionType),
					poolConfig, false);
		});

		try {

			StatefulConnection<?, ?> connection = pool.borrowObject();

			poolRef.put(connection, pool);

			return connectionType.cast(connection);
		} catch (Exception e) {
			throw new PoolException("Could not get a resource from the pool", e);
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider#getConnectionAsync(java.lang.Class)
	 */
	@Override
	public <T extends StatefulConnection<?, ?>> CompletionStage<T> getConnectionAsync(Class<T> connectionType) {

		AsyncPool<StatefulConnection<?, ?>> pool = asyncPools.computeIfAbsent(connectionType, poolType -> {

			return AsyncConnectionPoolSupport.createBoundedObjectPool(
					() -> connectionProvider.getConnectionAsync(connectionType).thenApply(connectionType::cast), asyncPoolConfig,
					false);
		});

		CompletableFuture<StatefulConnection<?, ?>> acquire = pool.acquire();

		inProgressAsyncPoolRef.put(acquire, pool);
		return acquire.whenComplete((connection, e) -> {

			inProgressAsyncPoolRef.remove(acquire);

			if (connection != null) {
				asyncPoolRef.put(connection, pool);
			}
		}).thenApply(connectionType::cast);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.lettuce.RedisClientProvider#getRedisClient()
	 */
	@Override
	public AbstractRedisClient getRedisClient() {

		if (connectionProvider instanceof RedisClientProvider) {
			return ((RedisClientProvider) connectionProvider).getRedisClient();
		}

		throw new IllegalStateException(
				String.format("Underlying connection provider %s does not implement RedisClientProvider!",
						connectionProvider.getClass().getName()));
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider#release(io.lettuce.core.api.StatefulConnection)
	 */
	@Override
	public void release(StatefulConnection<?, ?> connection) {

		/* ===== L3-05 讲解注释 BY 军火库 =====
		 * <h3>🔥 池化策略 · dedicated 连接归还点</h3>
		 *
		 * <p><b>调用方</b>：{@code LettuceConnection#reset()} 释放 {@code asyncDedicatedConn}，
		 * {@code LettuceSubscription#doClose()} 释放 Pub/Sub 连接，以及其他 Provider 管理路径。</p>
		 *
		 * <p><b>触发条件</b>：业务命令生命周期结束，连接 wrapper close，或订阅关闭。</p>
		 *
		 * <p><b>做出的决定</b>：根据 borrow 时记录的 {@code poolRef}/{@code asyncPoolRef}
		 * 找到对应池；归还前调用 {@code discardIfNecessary(connection)}，防止处在 MULTI 状态的连接
		 * 被直接放回池污染下一个借用者。</p>
		 *
		 * <p><b>跳过它会怎样</b>：一是连接泄漏导致池耗尽；二是事务状态未清理导致下一个业务拿到
		 * “脏连接”，出现串包、命令入错事务队列、EXEC 返回异常等事故模式。
		 * ===== END ===== */
		GenericObjectPool<StatefulConnection<?, ?>> pool = poolRef.remove(connection);

		if (pool == null) {

			AsyncPool<StatefulConnection<?, ?>> asyncPool = asyncPoolRef.remove(connection);

			if (asyncPool == null) {
				throw new PoolException("Returned connection " + connection
						+ " was either previously returned or does not belong to this connection provider");
			}

			discardIfNecessary(connection);
			asyncPool.release(connection).join();
			return;
		}

		discardIfNecessary(connection);
		pool.returnObject(connection);
	}

	private void discardIfNecessary(StatefulConnection<?, ?> connection) {

		if (connection instanceof StatefulRedisConnection) {

			StatefulRedisConnection<?, ?> redisConnection = (StatefulRedisConnection<?, ?>) connection;
			if (redisConnection.isMulti()) {
				redisConnection.async().discard();
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider#releaseAsync(io.lettuce.core.api.StatefulConnection)
	 */
	@Override
	public CompletableFuture<Void> releaseAsync(StatefulConnection<?, ?> connection) {

		GenericObjectPool<StatefulConnection<?, ?>> blockingPool = poolRef.remove(connection);

		if (blockingPool != null) {

			log.warn("Releasing asynchronously a connection that was obtained from a non-blocking pool");
			blockingPool.returnObject(connection);
			return CompletableFuture.completedFuture(null);
		}

		AsyncPool<StatefulConnection<?, ?>> pool = asyncPoolRef.remove(connection);

		if (pool == null) {
			return LettuceFutureUtils.failed(new PoolException("Returned connection " + connection
					+ " was either previously returned or does not belong to this connection provider"));
		}

		return pool.release(connection);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.beans.factory.DisposableBean#destroy()
	 */
	@Override
	public void destroy() throws Exception {

		List<CompletableFuture<?>> futures = new ArrayList<>();
		if (!poolRef.isEmpty() || !asyncPoolRef.isEmpty()) {
			log.warn("LettucePoolingConnectionProvider contains unreleased connections");
		}

		if (!inProgressAsyncPoolRef.isEmpty()) {

			log.warn("LettucePoolingConnectionProvider has active connection retrievals");
			inProgressAsyncPoolRef.forEach((k, v) -> futures.add(k.thenApply(StatefulConnection::closeAsync)));
		}

		if (!poolRef.isEmpty()) {

			poolRef.forEach((connection, pool) -> pool.returnObject(connection));
			poolRef.clear();
		}

		if (!asyncPoolRef.isEmpty()) {

			asyncPoolRef.forEach((connection, pool) -> futures.add(pool.release(connection)));
			asyncPoolRef.clear();
		}

		pools.forEach((type, pool) -> pool.close());

		CompletableFuture
				.allOf(futures.stream().map(it -> it.exceptionally(LettuceFutureUtils.ignoreErrors()))
						.toArray(CompletableFuture[]::new)) //
				.thenCompose(ignored -> {

					CompletableFuture[] poolClose = asyncPools.values().stream().map(AsyncPool::closeAsync)
							.map(it -> it.exceptionally(LettuceFutureUtils.ignoreErrors())).toArray(CompletableFuture[]::new);

					return CompletableFuture.allOf(poolClose);
				}) //
				.thenRun(() -> {
					asyncPoolRef.clear();
					inProgressAsyncPoolRef.clear();
				}) //
				.join();

		pools.clear();
	}
}
