/*
 * Copyright 2011-2023 the original author or authors.
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

import static io.lettuce.core.protocol.CommandType.*;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.LettuceFutures;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TransactionResult;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.output.*;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.ProtocolKeyword;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.sentinel.api.StatefulRedisSentinelConnection;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.ExceptionTranslationStrategy;
import org.springframework.data.redis.FallbackExceptionTranslationStrategy;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.convert.TransactionResultConverter;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider.TargetAware;
import org.springframework.data.redis.connection.lettuce.LettuceResult.LettuceResultBuilder;
import org.springframework.data.redis.connection.lettuce.LettuceResult.LettuceStatusResult;
import org.springframework.data.redis.core.RedisCommand;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;

/**
 * {@code RedisConnection} implementation on top of <a href="https://github.com/mp911de/lettuce">Lettuce</a> Redis
 * client.
 *
 * <h3>L3-06 源码导读：LettuceConnection 是「Adapter」的标准教科书实现</h3>
 *
 * <p>这个类是 Spring Data Redis 把 Lettuce 客户端「翻译」成自己抽象 {@link RedisConnection}
 * 的核心适配器。在调用链路里它处于<b>承上启下</b>的位置：</p>
 * <ul>
 *   <li><b>对上</b>：实现 {@link RedisConnection} 全套命令族（Strings/Hash/List/Key/ZSet/Stream...），
 *       让 {@code RedisTemplate} 完全感知不到 Lettuce 的存在；</li>
 *   <li><b>对下</b>：组合一个共享 {@code asyncSharedConn} + 一个 {@code LettuceConnectionProvider}，
 *       通过 {@link #getAsyncConnection()} 在普通命令 / 事务 / Pipeline / 阻塞命令之间分流。</li>
 * </ul>
 *
 * <h4>L3-06 调试时重点观察</h4>
 * <ol>
 *   <li>上层 {@code RedisTemplate.execute} 拿到的 wrapper 对象 = 这里 new 出来的实例；</li>
 *   <li>每个 {@link #stringCommands()}/{@link #keyCommands()} 等子门面都返回新的 LettuceXxxCommands，
 *       它们再回过头调本类的 {@link #invoke()} —— 这是 Facade + Adapter 的组合；</li>
 *   <li>{@link #invoke()} → {@link LettuceInvoker} → Lettuce 原生 {@code RedisAsyncCommands}：
 *       从 SDR 抽象到 Lettuce 真实 IO 只需要 3 跳；</li>
 *   <li>异常路径：所有 {@code catch (Exception)} 都走 {@link #convertLettuceAccessException(Exception)}
 *       → {@link LettuceExceptionConverter} → {@code DataAccessException}。</li>
 * </ol>
 *
 * <h4>偷师要点</h4>
 * <ul>
 *   <li>Adapter 模式：上层接口不动，下层实现可换；</li>
 *   <li>Facade 模式：把 Lettuce 一堆命令族（StringAsyncCommands、ListAsyncCommands…）
 *       聚合成一个统一入口；</li>
 *   <li>Strategy + 异常翻译：{@code EXCEPTION_TRANSLATION} 是静态字段，所有异常路径共享。</li>
 * </ul>
 *
 * @author Costin Leau
 * @author Jennifer Hickey
 * @author Christoph Strobl
 * @author Thomas Darimont
 * @author David Liu
 * @author Mark Paluch
 * @author Ninad Divadkar
 * @author Tamil Selvan
 * @author ihaohong
 */
public class LettuceConnection extends AbstractRedisConnection {

	private final Log LOGGER = LogFactory.getLog(getClass());

	static final RedisCodec<byte[], byte[]> CODEC = ByteArrayCodec.INSTANCE;

	private static final ExceptionTranslationStrategy EXCEPTION_TRANSLATION = new FallbackExceptionTranslationStrategy(
			LettuceConverters.exceptionConverter());
	private static final TypeHints typeHints = new TypeHints();

	private final int defaultDbIndex;
	private int dbIndex;

	private final LettuceConnectionProvider connectionProvider;
	private final @Nullable StatefulConnection<byte[], byte[]> asyncSharedConn;
	private @Nullable StatefulConnection<byte[], byte[]> asyncDedicatedConn;

	private final long timeout;

	// refers only to main connection as pubsub happens on a different one
	private boolean isClosed = false;
	private boolean isMulti = false;
	private boolean isPipelined = false;
	private @Nullable List<LettuceResult<?, ?>> ppline;
	private @Nullable PipeliningFlushState flushState;
	private final Queue<FutureResult<?>> txResults = new LinkedList<>();
	private volatile @Nullable LettuceSubscription subscription;
	/** flag indicating whether the connection needs to be dropped or not */
	private boolean convertPipelineAndTxResults = true;
	private PipeliningFlushPolicy pipeliningFlushPolicy = PipeliningFlushPolicy.flushEachCommand();

	LettuceResult<?, ?> newLettuceResult(Future<?> resultHolder) {
		return newLettuceResult(resultHolder, (val) -> val);
	}

	<T, R> LettuceResult<T, R> newLettuceResult(Future<T> resultHolder, Converter<T, R> converter) {

		return LettuceResultBuilder.<T, R> forResponse(resultHolder).mappedWith(converter)
				.convertPipelineAndTxResults(convertPipelineAndTxResults).build();
	}

	<T, R> LettuceResult<T, R> newLettuceResult(Future<T> resultHolder, Converter<T, R> converter,
			Supplier<R> defaultValue) {

		return LettuceResultBuilder.<T, R> forResponse(resultHolder).mappedWith(converter)
				.convertPipelineAndTxResults(convertPipelineAndTxResults).defaultNullTo(defaultValue).build();
	}

	<T, R> LettuceResult<T, R> newLettuceStatusResult(Future<T> resultHolder) {
		return LettuceResultBuilder.<T, R> forResponse(resultHolder).buildStatusResult();
	}

	private class LettuceTransactionResultConverter<T> extends TransactionResultConverter<T> {
		public LettuceTransactionResultConverter(Queue<FutureResult<T>> txResults,
				Converter<Exception, DataAccessException> exceptionConverter) {
			super(txResults, exceptionConverter);
		}

		@Override
		public List<Object> convert(List<Object> execResults) {
			// Lettuce Empty list means null (watched variable modified)
			if (execResults.isEmpty()) {
				return null;
			}
			return super.convert(execResults);
		}
	}

	/**
	 * Instantiates a new lettuce connection.
	 *
	 * @param timeout The connection timeout (in milliseconds)
	 * @param client The {@link RedisClient} to use when instantiating a native connection
	 */
	public LettuceConnection(long timeout, RedisClient client) {
		this(null, timeout, client, null);
	}

	/**
	 * Instantiates a new lettuce connection.
	 *
	 * @param timeout The connection timeout (in milliseconds) * @param client The {@link RedisClient} to use when
	 *          instantiating a pub/sub connection
	 * @param pool The connection pool to use for all other native connections
	 * @deprecated since 2.0, use pooling via {@link LettucePoolingClientConfiguration}.
	 */
	@Deprecated
	public LettuceConnection(long timeout, RedisClient client, LettucePool pool) {
		this(null, timeout, client, pool);
	}

	/**
	 * Instantiates a new lettuce connection.
	 *
	 * @param sharedConnection A native connection that is shared with other {@link LettuceConnection}s. Will not be used
	 *          for transactions or blocking operations
	 * @param timeout The connection timeout (in milliseconds)
	 * @param client The {@link RedisClient} to use when making pub/sub, blocking, and tx connections
	 */
	public LettuceConnection(@Nullable StatefulRedisConnection<byte[], byte[]> sharedConnection, long timeout,
			RedisClient client) {
		this(sharedConnection, timeout, client, null);
	}

	/**
	 * Instantiates a new lettuce connection.
	 *
	 * @param sharedConnection A native connection that is shared with other {@link LettuceConnection}s. Should not be
	 *          used for transactions or blocking operations
	 * @param timeout The connection timeout (in milliseconds)
	 * @param client The {@link RedisClient} to use when making pub/sub connections
	 * @param pool The connection pool to use for blocking and tx operations
	 * @deprecated since 2.0, use
	 *             {@link #LettuceConnection(StatefulRedisConnection, LettuceConnectionProvider, long, int)}
	 */
	@Deprecated
	public LettuceConnection(@Nullable StatefulRedisConnection<byte[], byte[]> sharedConnection, long timeout,
			RedisClient client, @Nullable LettucePool pool) {

		this(sharedConnection, timeout, client, pool, 0);
	}

	/**
	 * @param sharedConnection A native connection that is shared with other {@link LettuceConnection}s. Should not be
	 *          used for transactions or blocking operations.
	 * @param timeout The connection timeout (in milliseconds)
	 * @param client The {@link RedisClient} to use when making pub/sub connections.
	 * @param pool The connection pool to use for blocking and tx operations.
	 * @param defaultDbIndex The db index to use along with {@link RedisClient} when establishing a dedicated connection.
	 * @since 1.7
	 * @deprecated since 2.0, use
	 *             {@link #LettuceConnection(StatefulRedisConnection, LettuceConnectionProvider, long, int)}
	 */
	@Deprecated
	public LettuceConnection(@Nullable StatefulRedisConnection<byte[], byte[]> sharedConnection, long timeout,
			@Nullable AbstractRedisClient client, @Nullable LettucePool pool, int defaultDbIndex) {

		if (pool != null) {
			this.connectionProvider = new LettucePoolConnectionProvider(pool);
		} else {
			this.connectionProvider = new StandaloneConnectionProvider((RedisClient) client, CODEC);
		}

		this.asyncSharedConn = sharedConnection;
		this.timeout = timeout;
		this.defaultDbIndex = defaultDbIndex;
		this.dbIndex = this.defaultDbIndex;
	}

	/**
	 * @param sharedConnection A native connection that is shared with other {@link LettuceConnection}s. Should not be
	 *          used for transactions or blocking operations.
	 * @param connectionProvider connection provider to obtain and release native connections.
	 * @param timeout The connection timeout (in milliseconds)
	 * @param defaultDbIndex The db index to use along with {@link RedisClient} when establishing a dedicated connection.
	 * @since 2.0
	 */
	public LettuceConnection(@Nullable StatefulRedisConnection<byte[], byte[]> sharedConnection,
			LettuceConnectionProvider connectionProvider, long timeout, int defaultDbIndex) {
		this((StatefulConnection<byte[], byte[]>) sharedConnection, connectionProvider, timeout, defaultDbIndex);
	}

	/**
	 * @param sharedConnection A native connection that is shared with other {@link LettuceConnection}s. Should not be
	 *          used for transactions or blocking operations.
	 * @param connectionProvider connection provider to obtain and release native connections.
	 * @param timeout The connection timeout (in milliseconds)
	 * @param defaultDbIndex The db index to use along with {@link RedisClient} when establishing a dedicated connection.
	 * @since 2.1
	 */
	LettuceConnection(@Nullable StatefulConnection<byte[], byte[]> sharedConnection,
			LettuceConnectionProvider connectionProvider, long timeout, int defaultDbIndex) {

		Assert.notNull(connectionProvider, "LettuceConnectionProvider must not be null.");

		this.asyncSharedConn = sharedConnection;
		this.connectionProvider = connectionProvider;
		this.timeout = timeout;
		this.defaultDbIndex = defaultDbIndex;
		this.dbIndex = this.defaultDbIndex;
	}

	protected DataAccessException convertLettuceAccessException(Exception ex) {
		return EXCEPTION_TRANSLATION.translate(ex);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnection#geoCommands()
	 */
	@Override
	public RedisGeoCommands geoCommands() {
		return new LettuceGeoCommands(this);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnection#hashCommands()
	 */
	@Override
	public RedisHashCommands hashCommands() {
		return new LettuceHashCommands(this);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnection#hyperLogLogCommands()
	 */
	@Override
	public RedisHyperLogLogCommands hyperLogLogCommands() {
		return new LettuceHyperLogLogCommands(this);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnection#keyCommands()
	 */
	@Override
	public RedisKeyCommands keyCommands() {
		return new LettuceKeyCommands(this);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnection#listCommands()
	 */
	@Override
	public RedisListCommands listCommands() {
		return new LettuceListCommands(this);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnection#setCommands()
	 */
	@Override
	public RedisSetCommands setCommands() {
		return new LettuceSetCommands(this);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnection#scriptingCommands()
	 */
	@Override
	public RedisScriptingCommands scriptingCommands() {
		return new LettuceScriptingCommands(this);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnection#streamCommands()
	 */
	@Override
	public RedisStreamCommands streamCommands() {
		return new LettuceStreamCommands(this);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnection#stringCommands()
	 */
	@Override
	public RedisStringCommands stringCommands() {
		return new LettuceStringCommands(this);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnection#serverCommands()
	 */
	@Override
	public RedisServerCommands serverCommands() {
		return new LettuceServerCommands(this);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnection#zSetCommands()
	 */
	@Override
	public RedisZSetCommands zSetCommands() {
		return new LettuceZSetCommands(this);
	}

	@Override
	public Object execute(String command, byte[]... args) {
		return execute(command, null, args);
	}

	/**
	 * 'Native' or 'raw' execution of the given command along-side the given arguments.
	 *
	 * @see RedisConnection#execute(String, byte[]...)
	 * @param command Command to execute
	 * @param commandOutputTypeHint Type of Output to use, may be (may be {@literal null}).
	 * @param args Possible command arguments (may be {@literal null})
	 * @return execution result.
	 */
	@Nullable
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public Object execute(String command, @Nullable CommandOutput commandOutputTypeHint, byte[]... args) {

		Assert.hasText(command, "a valid command needs to be specified");

		String name = command.trim().toUpperCase();
		ProtocolKeyword commandType = getCommandType(name);

		validateCommandIfRunningInTransactionMode(commandType, args);

		CommandArgs<byte[], byte[]> cmdArg = new CommandArgs<>(CODEC);
		if (!ObjectUtils.isEmpty(args)) {
			cmdArg.addKeys(args);
		}

		CommandOutput expectedOutput = commandOutputTypeHint != null ? commandOutputTypeHint
				: typeHints.getTypeHint(commandType);
		Command cmd = new Command(commandType, expectedOutput, cmdArg);

		return invoke().just(RedisClusterAsyncCommands::dispatch, cmd.getType(), cmd.getOutput(), cmd.getArgs());
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.AbstractRedisConnection#close()
	 */
	/**
	 * Close this Spring Data Redis connection wrapper.
	 * <p>
	 * <b>L3-05 调试提示：</b>这里关闭的是 {@code LettuceConnection} wrapper。对于普通共享连接，
	 * close 不会关闭全局 {@code asyncSharedConn}；如果本 wrapper 曾经因为 pipeline、transaction
	 * 或 blocking command 创建过 {@code asyncDedicatedConn}，后续 {@code reset()} 会调用
	 * {@code connectionProvider.release(asyncDedicatedConn)} 归还或关闭专用连接。
	 * </p>
	 */
	@Override
	public void close() {

		super.close();

		if (isClosed) {
			return;
		}

		isClosed = true;

		try {
			reset();
		} catch (RuntimeException e) {
			LOGGER.debug("Failed to reset connection during close", e);
		}
	}

	private void reset() {

		/* ===== L3-05 讲解注释 BY 军火库 =====
		 * <h3>🔥 关键方法 · 专用连接释放入口</h3>
		 *
		 * <p><b>调用方</b>：{@link #close()}，通常由 {@code RedisTemplate.execute(...)} 的
		 * {@code finally} 通过 {@code RedisConnectionUtils.releaseConnection(...)} 间接触发。
		 * 手动拿 {@code RedisConnection} 时，业务代码必须自己 {@code close()}，否则这里不会执行。</p>
		 *
		 * <p><b>触发条件</b>：当前 {@code LettuceConnection} 生命周期结束，需要清理事务、Pipeline、
		 * 阻塞命令或订阅过程中持有的连接状态。</p>
		 *
		 * <p><b>做出的决定</b>：如果 {@code asyncDedicatedConn != null}，先把自定义 DB 切回默认 DB，
		 * 再交给 {@code connectionProvider.release(asyncDedicatedConn)}。底层如果是
		 * {@code LettucePoolingConnectionProvider}，这里是归还连接池；如果不是池化 Provider，
		 * 通常就是关闭或释放底层物理连接。</p>
		 *
		 * <p><b>跳过它会怎样</b>：事务、BLPOP、Pipeline 等路径借出的专用连接无法归还，
		 * 生产表现是连接池 active 持续升高、borrow 等待变长、Redis {@code CLIENT LIST}
		 * 连接数异常，最终误判成 Redis 慢或 max-active 太小。
		 * ===== END ===== */
		if (asyncDedicatedConn != null) {
			try {
				if (customizedDatabaseIndex()) {
					potentiallySelectDatabase(defaultDbIndex);
				}
				connectionProvider.release(asyncDedicatedConn);
				asyncDedicatedConn = null;
			} catch (RuntimeException ex) {
				throw convertLettuceAccessException(ex);
			}
		}

		LettuceSubscription subscription = this.subscription;
		if (subscription != null) {
			if (subscription.isAlive()) {
				subscription.doClose();
			}
			this.subscription = null;
		}

		this.dbIndex = defaultDbIndex;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnection#isClosed()
	 */
	@Override
	public boolean isClosed() {
		return isClosed && !isSubscribed();
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnection#getNativeConnection()
	 */
	@Override
	public RedisClusterAsyncCommands<byte[], byte[]> getNativeConnection() {

		LettuceSubscription subscription = this.subscription;
		return (subscription != null && subscription.isAlive() ? subscription.getNativeConnection().async()
				: getAsyncConnection());
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnection#isQueueing()
	 */
	@Override
	public boolean isQueueing() {
		return isMulti;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnection#isPipelined()
	 */
	@Override
	public boolean isPipelined() {
		return isPipelined;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnection#openPipeline()
	 */
	/**
	 * Open pipeline mode for this wrapper.
	 * <p>
	 * <b>L3-05 调试提示：</b>当前版本 {@code openPipeline()} 不只是设置
	 * {@code isPipelined=true}，还会通过 {@code getOrCreateDedicatedConnection()} 让 pipeline
	 * 固定在专用连接上。第一次进入这里时，{@code asyncDedicatedConn} 往往从 {@code null}
	 * 变成真实 {@code StatefulConnection}。
	 * </p>
	 */
	@Override
	public void openPipeline() {

		if (!isPipelined) {
			isPipelined = true;
			ppline = new ArrayList<>();
			flushState = this.pipeliningFlushPolicy.newPipeline();
			flushState.onOpen(this.getOrCreateDedicatedConnection());
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnection#closePipeline()
	 */
	@Override
	public List<Object> closePipeline() {

		if (!isPipelined) {
			return Collections.emptyList();
		}

		flushState.onClose(this.getOrCreateDedicatedConnection());
		flushState = null;
		isPipelined = false;
		List<io.lettuce.core.protocol.RedisCommand<?, ?, ?>> futures = new ArrayList<>(ppline.size());
		for (LettuceResult<?, ?> result : ppline) {
			futures.add(result.getResultHolder());
		}

		try {
			boolean done = LettuceFutures.awaitAll(timeout, TimeUnit.MILLISECONDS,
					futures.toArray(new RedisFuture[futures.size()]));

			List<Object> results = new ArrayList<>(futures.size());

			Exception problem = null;

			if (done) {
				for (LettuceResult<?, ?> result : ppline) {

					if (result.getResultHolder().getOutput().hasError()) {

						Exception err = new InvalidDataAccessApiUsageException(result.getResultHolder().getOutput().getError());
						// remember only the first error
						if (problem == null) {
							problem = err;
						}
						results.add(err);
					} else if (!result.isStatus()) {

						try {
							results.add(result.conversionRequired() ? result.convert(result.get()) : result.get());
						} catch (DataAccessException e) {
							if (problem == null) {
								problem = e;
							}
							results.add(e);
						}
					}
				}
			}
			ppline.clear();

			if (problem != null) {
				throw new RedisPipelineException(problem, results);
			}

			if (done) {
				return results;
			}

			throw new RedisPipelineException(new QueryTimeoutException("Redis command timed out"));
		} catch (Exception e) {
			throw new RedisPipelineException(e);
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisServerCommands#shutdown(org.springframework.data.redis.connection.RedisServerCommands.ShutdownOption)
	 */
	@Override
	public byte[] echo(byte[] message) {
		return invoke().just(RedisClusterAsyncCommands::echo, message);
	}

	@Override
	public String ping() {
		return invoke().just(RedisClusterAsyncCommands::ping);
	}

	@Override
	public void discard() {
		isMulti = false;
		try {
			if (isPipelined()) {
				pipeline(newLettuceStatusResult(getAsyncDedicatedRedisCommands().discard()));
				return;
			}
			getDedicatedRedisCommands().discard();
		} catch (Exception ex) {
			throw convertLettuceAccessException(ex);
		} finally {
			txResults.clear();
		}
	}

	@Override
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public List<Object> exec() {

		isMulti = false;

		try {
			Converter<Exception, DataAccessException> exceptionConverter = this::convertLettuceAccessException;

			if (isPipelined()) {
				RedisFuture<TransactionResult> exec = getAsyncDedicatedRedisCommands().exec();

				LettuceTransactionResultConverter resultConverter = new LettuceTransactionResultConverter(
						new LinkedList<>(txResults), exceptionConverter);

				pipeline(newLettuceResult(exec,
						source -> resultConverter.convert(LettuceConverters.transactionResultUnwrapper().convert(source))));
				return null;
			}

			TransactionResult transactionResult = getDedicatedRedisCommands().exec();
			List<Object> results = LettuceConverters.transactionResultUnwrapper().convert(transactionResult);
			return convertPipelineAndTxResults
					? new LettuceTransactionResultConverter(txResults, exceptionConverter).convert(results)
					: results;
		} catch (Exception ex) {
			throw convertLettuceAccessException(ex);
		} finally {
			txResults.clear();
		}
	}

	@Override
	public void multi() {
		/* ===== L3-05 讲解注释 BY 军火库 =====
		 * <h3>🔥 关键方法 · MULTI 事务入口</h3>
		 *
		 * <p><b>调用方</b>：业务通过 {@code RedisConnection.multi()}、
		 * {@code RedisTemplate.execute(SessionCallback)} 或 Spring Data Redis 事务支持进入。</p>
		 *
		 * <p><b>触发条件</b>：优惠券领取、库存扣减等需要 Redis 事务队列时调用 {@code MULTI}。
		 * {@code MULTI} 是连接级状态，后续命令必须留在同一条连接的事务队列里。</p>
		 *
		 * <p><b>做出的决定</b>：先把 {@code isMulti=true}，随后无论同步还是 Pipeline 场景，
		 * 都使用 dedicated 命令接口发送 {@code MULTI}，从而让事务上下文绑定在
		 * {@code asyncDedicatedConn} 上，而不是污染 {@code asyncSharedConn}。</p>
		 *
		 * <p><b>跳过它会怎样</b>：如果 MULTI 落到共享连接，其他商品详情页 GET、库存 INCR
		 * 可能被误塞进当前事务队列；如果 MULTI/EXEC 中途换连接，WATCH 和事务队列语义会断裂。
		 * ===== END ===== */
		if (isQueueing()) {
			return;
		}
		isMulti = true;
		try {
			if (isPipelined()) {
				getAsyncDedicatedRedisCommands().multi();
				return;
			}
			getDedicatedRedisCommands().multi();
		} catch (Exception ex) {
			throw convertLettuceAccessException(ex);
		}
	}

	@Override
	public void select(int dbIndex) {

		if (asyncSharedConn != null) {
			throw new UnsupportedOperationException("Selecting a new database not supported due to shared connection. "
					+ "Use separate ConnectionFactorys to work with multiple databases");
		}

		this.dbIndex = dbIndex;

		invokeStatus().just(RedisClusterAsyncCommands::dispatch, CommandType.SELECT,
				new StatusOutput<>(ByteArrayCodec.INSTANCE), new CommandArgs<>(ByteArrayCodec.INSTANCE).add(dbIndex));
	}

	@Override
	public void unwatch() {

		try {
			if (isPipelined()) {
				pipeline(newLettuceStatusResult(getAsyncDedicatedRedisCommands().unwatch()));
				return;
			}
			if (isQueueing()) {
				transaction(newLettuceStatusResult(getAsyncDedicatedRedisCommands().unwatch()));
				return;
			}
			getDedicatedRedisCommands().unwatch();
		} catch (Exception ex) {
			throw convertLettuceAccessException(ex);
		}
	}

	@Override
	public void watch(byte[]... keys) {
		/* ===== L3-05 讲解注释 BY 军火库 =====
		 * <h3>🔥 关键方法 · WATCH 乐观锁入口</h3>
		 *
		 * <p><b>调用方</b>：库存扣减、限时优惠券领取等乐观锁流程，典型链路是
		 * {@code WATCH -> GET -> MULTI -> DECR/SADD -> EXEC}。</p>
		 *
		 * <p><b>触发条件</b>：需要监控某些 key 是否被其他客户端修改，且监控状态必须保存在同一条
		 * Redis 连接上下文里。</p>
		 *
		 * <p><b>做出的决定</b>：非事务排队状态下，直接通过 {@code getDedicatedRedisCommands()}
		 * 获取专用连接并发送 WATCH。这样后续 MULTI/EXEC 可以继续复用同一条 dedicated 连接。</p>
		 *
		 * <p><b>跳过它会怎样</b>：WATCH 在连接 A 上执行，EXEC 却跑到连接 B 上，
		 * 业务以为做了乐观锁，实际监控状态已经丢失，抢券库存就可能出现超卖或异常失败率。
		 * ===== END ===== */
		if (isQueueing()) {
			throw new UnsupportedOperationException();
		}
		try {
			if (isPipelined()) {
				pipeline(newLettuceStatusResult(getAsyncDedicatedRedisCommands().watch(keys)));
				return;
			}
			if (isQueueing()) {
				transaction(new LettuceStatusResult(getAsyncDedicatedRedisCommands().watch(keys)));
				return;
			}
			getDedicatedRedisCommands().watch(keys);
		} catch (Exception ex) {
			throw convertLettuceAccessException(ex);
		}
	}

	//
	// Pub/Sub functionality
	//

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisPubSubCommands#publish(byte[], byte[])
	 */
	@Override
	public Long publish(byte[] channel, byte[] message) {
		return invoke().just(RedisClusterAsyncCommands::publish, channel, message);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisPubSubCommands#getSubscription()
	 */
	@Override
	public Subscription getSubscription() {
		return subscription;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisPubSubCommands#isSubscribed()
	 */
	@Override
	public boolean isSubscribed() {
		return (subscription != null && subscription.isAlive());
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisPubSubCommands#pSubscribe(org.springframework.data.redis.connection.MessageListener, byte[][])
	 */
	@Override
	public void pSubscribe(MessageListener listener, byte[]... patterns) {

		checkSubscription();

		if (isQueueing() || isPipelined()) {
			throw new UnsupportedOperationException("Transaction/Pipelining is not supported for Pub/Sub subscriptions!");
		}

		try {
			subscription = initSubscription(listener);
			subscription.pSubscribe(patterns);
		} catch (Exception ex) {
			throw convertLettuceAccessException(ex);
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisPubSubCommands#subscribe(org.springframework.data.redis.connection.MessageListener, byte[][])
	 */
	@Override
	public void subscribe(MessageListener listener, byte[]... channels) {
		/* ===== L3-05 讲解注释 BY 军火库 =====
		 * <h3>🔥 关键方法 · SUBSCRIBE 订阅入口</h3>
		 *
		 * <p><b>调用方</b>：业务直接调用 {@code RedisConnection.subscribe(...)}，
		 * 或上层监听容器为了实时门店库存广播、配置变更通知等场景建立订阅。</p>
		 *
		 * <p><b>触发条件</b>：Redis {@code SUBSCRIBE} 会把连接切到 Pub/Sub 协议状态，
		 * 服务端开始持续推送消息，这条连接不再适合普通请求-响应命令。</p>
		 *
		 * <p><b>做出的决定</b>：先拒绝事务/Pipeline 状态下订阅，再通过
		 * {@code initSubscription(listener)} -> {@code switchToPubSub()} 获取
		 * {@code StatefulRedisPubSubConnection}。注意：Pub/Sub 走自己的专用连接类型，
		 * 不复用 {@code asyncDedicatedConn} 字段。</p>
		 *
		 * <p><b>跳过它会怎样</b>：如果订阅连接和普通 GET/SET/INCR 混用，
		 * 连接协议语义已经变成推送模式，普通业务请求会出现无法执行、响应错位或连接长期悬挂。
		 * ===== END ===== */

		checkSubscription();

		if (isQueueing() || isPipelined()) {
			throw new UnsupportedOperationException("Transaction/Pipelining is not supported for Pub/Sub subscriptions!");
		}

		try {
			subscription = initSubscription(listener);
			subscription.subscribe(channels);
		} catch (Exception ex) {
			throw convertLettuceAccessException(ex);
		}
	}

	@SuppressWarnings("unchecked")
	<T> T failsafeReadScanValues(List<?> source, @SuppressWarnings("rawtypes") Converter converter) {

		try {
			return (T) (converter != null ? converter.convert(source) : source);
		} catch (IndexOutOfBoundsException e) {
			// ignore this one
		}
		return null;
	}

	/**
	 * Specifies if pipelined and transaction results should be converted to the expected data type. If false, results of
	 * {@link #closePipeline()} and {@link #exec()} will be of the type returned by the Lettuce driver
	 *
	 * @param convertPipelineAndTxResults Whether or not to convert pipeline and tx results
	 */
	public void setConvertPipelineAndTxResults(boolean convertPipelineAndTxResults) {
		this.convertPipelineAndTxResults = convertPipelineAndTxResults;
	}

	/**
	 * Configures the flushing policy when using pipelining.
	 *
	 * @param pipeliningFlushPolicy the flushing policy to control when commands get written to the Redis connection.
	 * @see PipeliningFlushPolicy#flushEachCommand()
	 * @see #openPipeline()
	 * @see StatefulRedisConnection#flushCommands()
	 * @since 2.3
	 */
	public void setPipeliningFlushPolicy(PipeliningFlushPolicy pipeliningFlushPolicy) {

		Assert.notNull(pipeliningFlushPolicy, "PipeliningFlushingPolicy must not be null!");

		this.pipeliningFlushPolicy = pipeliningFlushPolicy;
	}

	/**
	 * {@link #close()} the current connection and open a new pub/sub connection to the Redis server.
	 *
	 * @return never {@literal null}.
	 */
	@SuppressWarnings("unchecked")
	protected StatefulRedisPubSubConnection<byte[], byte[]> switchToPubSub() {

		/* ===== L3-05 讲解注释 BY 军火库 =====
		 * <h3>🔥 关键方法 · Pub/Sub 专用连接切换点</h3>
		 *
		 * <p><b>调用方</b>：{@code initSubscription(listener)}，由 {@code subscribe()} 和
		 * {@code pSubscribe()} 触发。</p>
		 *
		 * <p><b>触发条件</b>：应用需要进入 Redis Pub/Sub 订阅模式，例如实时门店库存广播、
		 * 运营活动开关推送、缓存失效通知。</p>
		 *
		 * <p><b>做出的决定</b>：先 {@code reset()} 当前连接状态，再向
		 * {@code connectionProvider} 申请 {@code StatefulRedisPubSubConnection}。
		 * 这条连接交给 {@code LettuceSubscription} 管理生命周期，不写入
		 * {@code asyncDedicatedConn} 字段。</p>
		 *
		 * <p><b>跳过它会怎样</b>：订阅会污染普通命令连接。Redis SUBSCRIBE 后连接进入推送协议状态，
		 * 如果商品详情 GET、SKU 价格查询继续复用这条连接，轻则命令被拒绝，重则连接长期悬挂。
		 * ===== END ===== */
		checkSubscription();
		reset();
		return connectionProvider.getConnection(StatefulRedisPubSubConnection.class);
	}

	/**
	 * Customization hook to create a {@link LettuceSubscription}.
	 *
	 * @param listener the {@link MessageListener} to notify.
	 * @param connection Pub/Sub connection.
	 * @param connectionProvider the {@link LettuceConnectionProvider} for connection release.
	 * @return a {@link LettuceSubscription}.
	 * @since 2.2
	 */
	protected LettuceSubscription doCreateSubscription(MessageListener listener,
			StatefulRedisPubSubConnection<byte[], byte[]> connection, LettuceConnectionProvider connectionProvider) {
		return new LettuceSubscription(listener, connection, connectionProvider);
	}

	void pipeline(LettuceResult<?, ?> result) {

		if (flushState != null) {
			flushState.onCommand(getOrCreateDedicatedConnection());
		}

		if (isQueueing()) {
			transaction(result);
		} else {
			ppline.add(result);
		}
	}

	/**
	 * Obtain a {@link LettuceInvoker} to call Lettuce methods using the default {@link #getAsyncConnection() connection}.
	 *
	 * <h3>L3-06 适配视角</h3>
	 * <p>这是 LettuceXxxCommands 子门面进入「真正调 Lettuce 原生 API」的标准入口。
	 * 它做了三件事：</p>
	 * <ol>
	 *   <li>调用 {@link #getAsyncConnection()} 选一条合适的 native connection（shared 或 dedicated）；</li>
	 *   <li>把这条连接交给 {@link LettuceInvoker} 包成「函数式调用器」；</li>
	 *   <li>由 {@code doInvoke} 决定 future 走「立即 await」「pipeline 队列」还是「事务队列」。</li>
	 * </ol>
	 * <p>这是 Spring 风格的典型范式：把<b>命令的 What</b>（方法引用）和<b>How</b>（同步/Pipeline/事务）
	 * 完全解耦。</p>
	 *
	 * @return the {@link LettuceInvoker}.
	 * @since 2.5
	 */
	LettuceInvoker invoke() {
		return invoke(getAsyncConnection());
	}

	/**
	 * Obtain a {@link LettuceInvoker} to call Lettuce methods using the given {@link RedisClusterAsyncCommands
	 * connection}.
	 *
	 * @param connection the connection to use.
	 * @return the {@link LettuceInvoker}.
	 * @since 2.5
	 */
	LettuceInvoker invoke(RedisClusterAsyncCommands<byte[], byte[]> connection) {
		return doInvoke(connection, false);
	}

	/**
	 * Obtain a {@link LettuceInvoker} to call Lettuce methods returning a status response using the default
	 * {@link #getAsyncConnection() connection}. Status responses are not included in transactional and pipeline results.
	 *
	 * @return the {@link LettuceInvoker}.
	 * @since 2.5
	 */
	LettuceInvoker invokeStatus() {
		return doInvoke(getAsyncConnection(), true);
	}

	private LettuceInvoker doInvoke(RedisClusterAsyncCommands<byte[], byte[]> connection, boolean statusCommand) {

		/* ===== L3-05 讲解注释 BY 军火库 =====
		 * <h3>🔥 关键方法 · 命令执行结果分发器</h3>
		 *
		 * <p><b>调用方</b>：大多数 Lettuce 命令适配类都会调用 {@code invoke()} 或
		 * {@code invoke(connection)}，例如字符串命令、List 命令、事务/Pipeline 内的命令。</p>
		 *
		 * <p><b>触发条件</b>：每次需要把 Lettuce 的 {@code RedisFuture} 转成 Spring Data Redis
		 * 的同步返回、Pipeline 结果或事务结果时触发。</p>
		 *
		 * <p><b>做出的决定</b>：本方法不直接创建 dedicated 连接；它信任传入的
		 * {@code connection} 参数。这个参数要么来自 {@code getAsyncConnection()} 的共享/独占选择，
		 * 要么由 BLPOP 等特殊命令显式传入 {@code getAsyncDedicatedConnection()}。
		 * 随后它按 {@code isPipelined()}、{@code isQueueing()} 决定结果进入 Pipeline 列表、
		 * 事务队列，还是立即 await 返回。</p>
		 *
		 * <p><b>跳过它会怎样</b>：命令结果生命周期会散落在各个命令实现里，事务结果、Pipeline
		 * 结果和普通同步结果难以保持一致；更严重的是特殊命令即便拿到了 dedicated 连接，
		 * 结果也可能被错误地立即 await 或错误地进入事务队列。
		 * ===== END ===== */
		if (isPipelined()) {

			return new LettuceInvoker(connection, (future, converter, nullDefault) -> {

				try {
					if (statusCommand) {
						pipeline(newLettuceStatusResult(future.get()));
					} else {
						pipeline(newLettuceResult(future.get(), converter, nullDefault));
					}
				} catch (Exception ex) {
					throw convertLettuceAccessException(ex);
				}
				return null;
			});
		}

		if (isQueueing()) {

			return new LettuceInvoker(connection, (future, converter, nullDefault) -> {
				try {
					if (statusCommand) {
						transaction(newLettuceStatusResult(future.get()));
					} else {
						transaction(newLettuceResult(future.get(), converter, nullDefault));
					}

				} catch (Exception ex) {
					throw convertLettuceAccessException(ex);
				}
				return null;
			});
		}

		return new LettuceInvoker(connection, (future, converter, nullDefault) -> {

			try {

				Object result = await(future.get());

				if (result == null) {
					return nullDefault.get();
				}

				return converter.convert(result);
			} catch (Exception ex) {
				throw convertLettuceAccessException(ex);
			}
		});
	}

	void transaction(FutureResult<?> result) {
		txResults.add(result);
	}

	/**
	 * Obtain async commands for regular command execution.
	 * <p>
	 * <b>L3-05 调试提示：</b>这是 shared/dedicated 的核心分流点：
	 * </p>
	 * <ul>
	 * <li>如果 {@link #isQueueing()} 或 {@link #isPipelined()} 为 {@code true}，走
	 * {@link #getAsyncDedicatedConnection()}。</li>
	 * <li>否则如果 {@code asyncSharedConn != null}，复用 shared native connection。</li>
	 * <li>否则说明共享连接不可用，例如 {@code shareNativeConnection=false}，普通命令也退到
	 * dedicated 路径。</li>
	 * </ul>
	 *
	 * @return Lettuce async command interface backed by shared or dedicated connection.
	 */
	RedisClusterAsyncCommands<byte[], byte[]> getAsyncConnection() {

		/* ===== L3-05 讲解注释 BY 军火库 =====
		 * <h3>🔥 关键方法 · 普通异步命令的共享/独占分发口</h3>
		 *
		 * <p><b>调用方</b>：{@code invoke()}、{@code invokeStatus()} 以及大部分普通 Redis
		 * 命令适配路径。</p>
		 *
		 * <p><b>触发条件</b>：每次没有显式指定连接的命令需要获取 Lettuce async commands 时触发，
		 * 例如商品详情页 {@code GET}、SKU 批量查询 {@code MGET}、签到位图 {@code SETBIT}。</p>
		 *
		 * <p><b>做出的决定</b>：如果当前正在事务排队或 Pipeline，就强制返回 dedicated async
		 * commands；否则优先复用 {@code asyncSharedConn}。当 {@code asyncSharedConn == null}
		 * 时（例如 {@code shareNativeConnection=false}），普通命令也会退到
		 * {@code getAsyncDedicatedConnection()}。</p>
		 *
		 * <p><b>跳过它会怎样</b>：普通短命令和事务/Pipeline 命令没有统一分流点，
		 * 要么全部走共享导致上下文污染，要么全部走专用导致连接数暴涨、池竞争增加。
		 * ===== END ===== */
		if (isQueueing() || isPipelined()) {
			return getAsyncDedicatedConnection();
		}

		if (asyncSharedConn != null) {

			if (asyncSharedConn instanceof StatefulRedisConnection) {
				return ((StatefulRedisConnection<byte[], byte[]>) asyncSharedConn).async();
			}
			if (asyncSharedConn instanceof StatefulRedisClusterConnection) {
				return ((StatefulRedisClusterConnection<byte[], byte[]>) asyncSharedConn).async();
			}
		}
		return getAsyncDedicatedConnection();
	}

	protected RedisClusterCommands<byte[], byte[]> getConnection() {

		if (isQueueing()) {
			return getDedicatedConnection();
		}

		if (asyncSharedConn != null) {

			if (asyncSharedConn instanceof StatefulRedisConnection) {
				return ((StatefulRedisConnection<byte[], byte[]>) asyncSharedConn).sync();
			}
			if (asyncSharedConn instanceof StatefulRedisClusterConnection) {
				return ((StatefulRedisClusterConnection<byte[], byte[]>) asyncSharedConn).sync();
			}
		}

		return getDedicatedConnection();
	}

	RedisClusterCommands<byte[], byte[]> getDedicatedConnection() {

		StatefulConnection<byte[], byte[]> connection = getOrCreateDedicatedConnection();

		if (connection instanceof StatefulRedisConnection) {
			return ((StatefulRedisConnection<byte[], byte[]>) connection).sync();
		}
		if (connection instanceof StatefulRedisClusterConnection) {
			return ((StatefulRedisClusterConnection<byte[], byte[]>) connection).sync();
		}

		throw new IllegalStateException(
				String.format("%s is not a supported connection type.", connection.getClass().getName()));
	}

	/**
	 * Obtain async commands backed by the dedicated native connection.
	 * <p>
	 * <b>L3-05 调试提示：</b>本方法是事务、pipeline、blocking command 等路径进入
	 * {@code asyncDedicatedConn} 的统一入口。它会先检查 wrapper 是否关闭，再调用
	 * {@code getOrCreateDedicatedConnection()}。真正向 provider 借连接的动作发生在
	 * {@code doGetAsyncDedicatedConnection()}。
	 * </p>
	 *
	 * @return Lettuce async command interface backed by {@code asyncDedicatedConn}.
	 */
	protected RedisClusterAsyncCommands<byte[], byte[]> getAsyncDedicatedConnection() {

		/* ===== L3-05 讲解注释 BY 军火库 =====
		 * <h3>🔥 关键方法 · asyncDedicatedConn 懒加载入口</h3>
		 *
		 * <p><b>调用方</b>：事务/Pipeline 状态下的 {@code getAsyncConnection()}，
		 * 以及 {@code LettuceListCommands#bLPop}、{@code LettuceZSetCommands#bZPopMin}、
		 * {@code LettuceStreamCommands#xRead} 等阻塞命令路径。</p>
		 *
		 * <p><b>触发条件</b>：当前命令需要独占连接上下文，或者 Factory 没有注入共享连接
		 * （{@code shareNativeConnection=false}），且调用方需要 async commands。</p>
		 *
		 * <p><b>做出的决定</b>：先校验连接未关闭，再调用 {@code getOrCreateDedicatedConnection()}
		 * 拿到当前 wrapper 绑定的 {@code StatefulConnection}，最后按 Standalone/Cluster 类型取
		 * {@code async()}。</p>
		 *
		 * <p><b>跳过它会怎样</b>：BLPOP、XREAD BLOCK、事务内命令会落到共享 Netty Channel，
		 * 一旦某个命令长期等待或改变连接状态，就会拖累商品详情、库存查询等核心短命令。
		 * ===== END ===== */
		if (isClosed()) {
			throw new RedisSystemException("Connection is closed", null);
		}

		StatefulConnection<byte[], byte[]> connection = getOrCreateDedicatedConnection();

		if (connection instanceof StatefulRedisConnection) {
			return ((StatefulRedisConnection<byte[], byte[]>) connection).async();
		}
		if (asyncDedicatedConn instanceof StatefulRedisClusterConnection) {
			return ((StatefulRedisClusterConnection<byte[], byte[]>) connection).async();
		}

		throw new IllegalStateException(
				String.format("%s is not a supported connection type.", connection.getClass().getName()));
	}

	@SuppressWarnings("unchecked")
	protected StatefulConnection<byte[], byte[]> doGetAsyncDedicatedConnection() {

		/* ===== L3-05 讲解注释 BY 军火库 =====
		 * <h3>🔥 关键方法 · 专用 StatefulConnection 实际获取点</h3>
		 *
		 * <p><b>调用方</b>：只由 {@code getOrCreateDedicatedConnection()} 在
		 * {@code asyncDedicatedConn == null} 时调用。</p>
		 *
		 * <p><b>触发条件</b>：当前 {@code LettuceConnection} 第一次需要 dedicated 连接，
		 * 例如第一次 WATCH、MULTI、BLPOP 或 {@code shareNativeConnection=false} 下第一次普通命令。</p>
		 *
		 * <p><b>做出的决定</b>：委托 {@code connectionProvider.getConnection(StatefulConnection.class)}
		 * 获取连接。Provider 背后可以是简单新建连接，也可以是 {@code LettucePoolingConnectionProvider}
		 * 从 Commons Pool 里 borrow。</p>
		 *
		 * <p><b>跳过它会怎样</b>：{@code LettuceConnection} 就必须自己知道“新建连接、池化连接、
		 * Cluster 节点连接”的所有细节，连接策略会散落在业务适配层，无法替换和测试。
		 * ===== END ===== */
		StatefulConnection connection = connectionProvider.getConnection(StatefulConnection.class);

		if (customizedDatabaseIndex()) {
			potentiallySelectDatabase(dbIndex);
		}

		return connection;
	}

	@Override
	protected boolean isActive(RedisNode node) {

		StatefulRedisSentinelConnection<String, String> connection = null;
		try {
			connection = getConnection(node);
			return connection.sync().ping().equalsIgnoreCase("pong");
		} catch (Exception e) {
			return false;
		} finally {
			if (connection != null) {
				connectionProvider.release(connection);
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.AbstractRedisConnection#getSentinelConnection(org.springframework.data.redis.connection.RedisNode)
	 */
	@Override
	protected RedisSentinelConnection getSentinelConnection(RedisNode sentinel) {

		StatefulRedisSentinelConnection<String, String> connection = getConnection(sentinel);
		return new LettuceSentinelConnection(connection);
	}

	LettuceConnectionProvider getConnectionProvider() {
		return connectionProvider;
	}

	@SuppressWarnings("unchecked")
	private StatefulRedisSentinelConnection<String, String> getConnection(RedisNode sentinel) {
		return ((TargetAware) connectionProvider).getConnection(StatefulRedisSentinelConnection.class,
				getRedisURI(sentinel));
	}

	@Nullable
	private <T> T await(RedisFuture<T> cmd) {

		if (isMulti) {
			return null;
		}

		try {
			return LettuceFutures.awaitOrCancel(cmd, timeout, TimeUnit.MILLISECONDS);
		} catch (RuntimeException e) {
			throw convertLettuceAccessException(e);
		}
	}

	private StatefulConnection<byte[], byte[]> getOrCreateDedicatedConnection() {

		/* ===== L3-05 讲解注释 BY 军火库 =====
		 * <h3>🔥 关键方法 · asyncDedicatedConn 字段懒加载</h3>
		 *
		 * <p><b>调用方</b>：{@code getAsyncDedicatedConnection()}、{@code getDedicatedConnection()}、
		 * Pipeline flush state，以及事务/阻塞命令相关路径。</p>
		 *
		 * <p><b>触发条件</b>：当前 wrapper 第一次需要专用连接时，字段还是 {@code null}；
		 * 后续同一个 wrapper 生命周期内再次进入事务/阻塞/Pipeline，会复用这条连接。</p>
		 *
		 * <p><b>做出的决定</b>：把“需要时才创建”的 Lazy Initialization 收口在一个字段判断里，
		 * 避免每次普通命令都提前占用 dedicated 资源。</p>
		 *
		 * <p><b>跳过它会怎样</b>：要么每个 {@code LettuceConnection} 创建时都多开一条连接，
		 * 造成线上连接数膨胀；要么每个特殊命令都重新借还连接，破坏事务/WATCH 必须同连接的语义。
		 * ===== END ===== */
		if (asyncDedicatedConn == null) {
			asyncDedicatedConn = doGetAsyncDedicatedConnection();
		}

		return asyncDedicatedConn;
	}

	@SuppressWarnings("unchecked")
	private RedisCommands<byte[], byte[]> getDedicatedRedisCommands() {
		return (RedisCommands) getDedicatedConnection();
	}

	@SuppressWarnings("unchecked")
	private RedisAsyncCommands<byte[], byte[]> getAsyncDedicatedRedisCommands() {
		/* ===== L3-05 讲解注释 BY 军火库 =====
		 * <h3>🔥 关键方法 · dedicated async commands 适配口</h3>
		 *
		 * <p><b>调用方</b>：事务和 Pipeline 组合路径，例如 {@code multi()}、
		 * {@code exec()}、{@code discard()}、{@code watch()} 在 Pipeline/queueing 状态下调用。</p>
		 *
		 * <p><b>触发条件</b>：需要把 dedicated 的 Cluster async commands 窄化为 Redis async commands，
		 * 以调用 {@code multi()}、{@code exec()}、{@code watch()} 等事务命令。</p>
		 *
		 * <p><b>做出的决定</b>：复用 {@code getAsyncDedicatedConnection()} 的懒加载与类型判断，
		 * 这里只做命令接口转换。</p>
		 *
		 * <p><b>跳过它会怎样</b>：事务命令需要在多个位置重复强转和懒加载逻辑，
		 * 更容易把某个分支误接到 shared async commands。
		 * ===== END ===== */
		return (RedisAsyncCommands) getAsyncDedicatedConnection();
	}

	private void checkSubscription() {
		if (isSubscribed()) {
			throw new RedisSubscribedConnectionException(
					"Connection already subscribed; use the connection Subscription to cancel or add new channels");
		}
	}

	private LettuceSubscription initSubscription(MessageListener listener) {
		return doCreateSubscription(listener, switchToPubSub(), connectionProvider);
	}

	private RedisURI getRedisURI(RedisNode node) {
		return RedisURI.Builder.redis(node.getHost(), node.getPort()).build();
	}

	private boolean customizedDatabaseIndex() {
		return defaultDbIndex != dbIndex;
	}

	private void potentiallySelectDatabase(int dbIndex) {
		if (asyncDedicatedConn instanceof StatefulRedisConnection) {
			((StatefulRedisConnection<byte[], byte[]>) asyncDedicatedConn).sync().select(dbIndex);
		}
	}

	io.lettuce.core.ScanCursor getScanCursor(long cursorId) {
		return io.lettuce.core.ScanCursor.of(Long.toString(cursorId));
	}

	private void validateCommandIfRunningInTransactionMode(ProtocolKeyword cmd, byte[]... args) {

		if (this.isQueueing()) {
			validateCommand(cmd, args);
		}
	}

	private void validateCommand(ProtocolKeyword cmd, @Nullable byte[]... args) {

		RedisCommand redisCommand = RedisCommand.failsafeCommandLookup(cmd.name());
		if (!RedisCommand.UNKNOWN.equals(redisCommand) && redisCommand.requiresArguments()) {
			try {
				redisCommand.validateArgumentCount(args != null ? args.length : 0);
			} catch (IllegalArgumentException e) {
				throw new InvalidDataAccessApiUsageException(String.format("Validation failed for %s command.", cmd), e);
			}
		}
	}

	private static ProtocolKeyword getCommandType(String name) {

		try {
			return CommandType.valueOf(name);
		} catch (IllegalArgumentException e) {
			return new CustomCommandType(name);
		}
	}

	/**
	 * {@link TypeHints} provide {@link CommandOutput} information for a given {@link CommandType}.
	 *
	 * @since 1.2.1
	 */
	static class TypeHints {

		@SuppressWarnings("rawtypes") //
		private static final Map<ProtocolKeyword, Class<? extends CommandOutput>> COMMAND_OUTPUT_TYPE_MAPPING = new HashMap<>();

		@SuppressWarnings("rawtypes") //
		private static final Map<Class<?>, Constructor<CommandOutput>> CONSTRUCTORS = new ConcurrentHashMap<>();

		{
			// INTEGER
			COMMAND_OUTPUT_TYPE_MAPPING.put(BITCOUNT, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(BITOP, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(BITPOS, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(DBSIZE, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(DECR, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(DECRBY, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(DEL, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(GETBIT, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(HDEL, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(HINCRBY, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(HLEN, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(HSTRLEN, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(INCR, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(INCRBY, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(LINSERT, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(LLEN, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(LPUSH, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(LPOS, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(LPUSHX, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(LREM, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(PTTL, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(PUBLISH, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(RPUSH, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(RPUSHX, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SADD, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SCARD, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SDIFFSTORE, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SETBIT, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SETRANGE, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SINTERSTORE, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SREM, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SUNIONSTORE, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(STRLEN, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(TTL, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(XLEN, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(XTRIM, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(ZADD, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(ZCARD, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(ZCOUNT, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(ZINTERSTORE, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(ZRANK, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(ZREM, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(ZREMRANGEBYRANK, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(ZREMRANGEBYSCORE, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(ZREVRANK, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(ZUNIONSTORE, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(PFCOUNT, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(PFMERGE, IntegerOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(PFADD, IntegerOutput.class);

			// DOUBLE
			COMMAND_OUTPUT_TYPE_MAPPING.put(HINCRBYFLOAT, DoubleOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(INCRBYFLOAT, DoubleOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(ZINCRBY, DoubleOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(ZSCORE, DoubleOutput.class);

			// DOUBLE LIST
			COMMAND_OUTPUT_TYPE_MAPPING.put(ZMSCORE, DoubleListOutput.class);

			// MAP
			COMMAND_OUTPUT_TYPE_MAPPING.put(HGETALL, MapOutput.class);

			// KEY LIST
			COMMAND_OUTPUT_TYPE_MAPPING.put(HKEYS, KeyListOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(KEYS, KeyListOutput.class);

			// KEY VALUE
			COMMAND_OUTPUT_TYPE_MAPPING.put(BRPOP, KeyValueOutput.class);

			// SINGLE VALUE
			COMMAND_OUTPUT_TYPE_MAPPING.put(BRPOPLPUSH, ValueOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(ECHO, ValueOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(GET, ValueOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(GETRANGE, ValueOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(GETSET, ValueOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(HGET, ValueOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(LINDEX, ValueOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(LPOP, ValueOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(RANDOMKEY, ValueOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(RENAME, ValueOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(RPOP, ValueOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(RPOPLPUSH, ValueOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SPOP, ValueOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SRANDMEMBER, ValueOutput.class);

			// STATUS VALUE
			COMMAND_OUTPUT_TYPE_MAPPING.put(BGREWRITEAOF, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(BGSAVE, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(CLIENT, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(DEBUG, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(DISCARD, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(FLUSHALL, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(FLUSHDB, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(HMSET, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(INFO, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(LSET, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(LTRIM, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(MIGRATE, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(MSET, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(QUIT, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(RESTORE, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SAVE, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SELECT, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SET, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SETEX, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SHUTDOWN, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SLAVEOF, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SYNC, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(TYPE, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(WATCH, StatusOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(UNWATCH, StatusOutput.class);

			// VALUE LIST
			COMMAND_OUTPUT_TYPE_MAPPING.put(HMGET, ValueListOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(MGET, ValueListOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(HVALS, ValueListOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(LRANGE, ValueListOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SORT, ValueListOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(ZRANGE, ValueListOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(ZRANGEBYSCORE, ValueListOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(ZREVRANGE, ValueListOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(ZREVRANGEBYSCORE, ValueListOutput.class);

			// BOOLEAN
			COMMAND_OUTPUT_TYPE_MAPPING.put(EXISTS, BooleanOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(EXPIRE, BooleanOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(EXPIREAT, BooleanOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(HEXISTS, BooleanOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(HSET, BooleanOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(HSETNX, BooleanOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(MOVE, BooleanOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(COPY, BooleanOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(MSETNX, BooleanOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(PERSIST, BooleanOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(PEXPIRE, BooleanOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(PEXPIREAT, BooleanOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(RENAMENX, BooleanOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SETNX, BooleanOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SISMEMBER, BooleanOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SMOVE, BooleanOutput.class);

			// MULTI
			COMMAND_OUTPUT_TYPE_MAPPING.put(EXEC, MultiOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(MULTI, MultiOutput.class);

			// DATE
			COMMAND_OUTPUT_TYPE_MAPPING.put(LASTSAVE, DateOutput.class);

			// VALUE SET
			COMMAND_OUTPUT_TYPE_MAPPING.put(SDIFF, ValueSetOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SINTER, ValueSetOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SMEMBERS, ValueSetOutput.class);
			COMMAND_OUTPUT_TYPE_MAPPING.put(SUNION, ValueSetOutput.class);
		}

		/**
		 * Returns the {@link CommandOutput} mapped for given {@link CommandType} or {@link ByteArrayOutput} as default.
		 *
		 * @param type
		 * @return {@link ByteArrayOutput} as default when no matching {@link CommandOutput} available.
		 */
		@SuppressWarnings("rawtypes")
		public CommandOutput getTypeHint(ProtocolKeyword type) {
			return getTypeHint(type, new ByteArrayOutput<>(CODEC));
		}

		/**
		 * Returns the {@link CommandOutput} mapped for given {@link CommandType} given {@link CommandOutput} as default.
		 *
		 * @param type
		 * @return
		 */
		@SuppressWarnings("rawtypes")
		public CommandOutput getTypeHint(ProtocolKeyword type, CommandOutput defaultType) {

			if (type == null || !COMMAND_OUTPUT_TYPE_MAPPING.containsKey(type)) {
				return defaultType;
			}
			CommandOutput<?, ?, ?> outputType = instanciateCommandOutput(COMMAND_OUTPUT_TYPE_MAPPING.get(type));
			return outputType != null ? outputType : defaultType;
		}

		@SuppressWarnings({ "rawtypes", "unchecked" })
		private CommandOutput<?, ?, ?> instanciateCommandOutput(Class<? extends CommandOutput> type) {

			Assert.notNull(type, "Cannot create instance for 'null' type.");
			Constructor<CommandOutput> constructor = CONSTRUCTORS.get(type);
			if (constructor == null) {
				constructor = (Constructor<CommandOutput>) ClassUtils.getConstructorIfAvailable(type, RedisCodec.class);
				CONSTRUCTORS.put(type, constructor);
			}
			return BeanUtils.instantiateClass(constructor, CODEC);
		}
	}

	static class LettucePoolConnectionProvider implements LettuceConnectionProvider {

		private final LettucePool pool;

		LettucePoolConnectionProvider(LettucePool pool) {
			this.pool = pool;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider#getConnection(java.lang.Class)
		 */
		@Override
		public <T extends StatefulConnection<?, ?>> T getConnection(Class<T> connectionType) {
			return connectionType.cast(pool.getResource());
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider#getConnectionAsync(java.lang.Class)
		 */
		@Override
		public <T extends StatefulConnection<?, ?>> CompletionStage<T> getConnectionAsync(Class<T> connectionType) {
			throw new UnsupportedOperationException("Async operations not supported!");
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider#release(io.lettuce.core.api.StatefulConnection)
		 */
		@Override
		@SuppressWarnings("unchecked")
		public void release(StatefulConnection<?, ?> connection) {

			if (connection.isOpen()) {

				if (connection instanceof StatefulRedisConnection) {
					StatefulRedisConnection<?, ?> redisConnection = (StatefulRedisConnection<?, ?>) connection;
					if (redisConnection.isMulti()) {
						redisConnection.async().discard();
					}
				}
				pool.returnResource((StatefulConnection<byte[], byte[]>) connection);
			} else {
				pool.returnBrokenResource((StatefulConnection<byte[], byte[]>) connection);
			}
		}
	}

	/**
	 * Strategy interface to control pipelining flush behavior. Lettuce writes (flushes) each command individually to the
	 * Redis connection. Flushing behavior can be customized to optimize for performance. Flushing can be either stateless
	 * or stateful. An example for stateful flushing is size-based (buffer) flushing to flush after a configured number of
	 * commands.
	 *
	 * @see StatefulRedisConnection#setAutoFlushCommands(boolean)
	 * @see StatefulRedisConnection#flushCommands()
	 * @author Mark Paluch
	 * @since 2.3
	 */
	public interface PipeliningFlushPolicy {

		/**
		 * Return a policy to flush after each command (default behavior).
		 *
		 * @return a policy to flush after each command.
		 */
		static PipeliningFlushPolicy flushEachCommand() {
			return FlushEachCommand.INSTANCE;
		}

		/**
		 * Return a policy to flush only if {@link #closePipeline()} is called.
		 *
		 * @return a policy to flush after each command.
		 */
		static PipeliningFlushPolicy flushOnClose() {
			return FlushOnClose.INSTANCE;
		}

		/**
		 * Return a policy to buffer commands and to flush once reaching the configured {@code bufferSize}. The buffer is
		 * recurring so a buffer size of e.g. {@code 2} will flush after 2, 4, 6, … commands.
		 *
		 * @param bufferSize the number of commands to buffer before flushing. Must be greater than zero.
		 * @return a policy to flush buffered commands to the Redis connection once the configured number of commands was
		 *         issued.
		 */
		static PipeliningFlushPolicy buffered(int bufferSize) {

			Assert.isTrue(bufferSize > 0, "Buffer size must be greater than 0");
			return () -> new BufferedFlushing(bufferSize);
		}

		PipeliningFlushState newPipeline();
	}

	/**
	 * State object associated with flushing of the currently ongoing pipeline.
	 *
	 * @author Mark Paluch
	 * @since 2.3
	 */
	public interface PipeliningFlushState {

		/**
		 * Callback if the pipeline gets opened.
		 *
		 * @param connection
		 * @see #openPipeline()
		 */
		void onOpen(StatefulConnection<?, ?> connection);

		/**
		 * Callback for each issued Redis command.
		 *
		 * @param connection
		 * @see #pipeline(LettuceResult)
		 */
		void onCommand(StatefulConnection<?, ?> connection);

		/**
		 * Callback if the pipeline gets closed.
		 *
		 * @param connection
		 * @see #closePipeline()
		 */
		void onClose(StatefulConnection<?, ?> connection);
	}

	/**
	 * Implementation to flush on each command.
	 *
	 * @author Mark Paluch
	 * @since 2.3
	 */
	private enum FlushEachCommand implements PipeliningFlushPolicy, PipeliningFlushState {

		INSTANCE;

		@Override
		public PipeliningFlushState newPipeline() {
			return INSTANCE;
		}

		@Override
		public void onOpen(StatefulConnection<?, ?> connection) {}

		@Override
		public void onCommand(StatefulConnection<?, ?> connection) {}

		@Override
		public void onClose(StatefulConnection<?, ?> connection) {}
	}

	/**
	 * Implementation to flush on closing the pipeline.
	 *
	 * @author Mark Paluch
	 * @since 2.3
	 */
	private enum FlushOnClose implements PipeliningFlushPolicy, PipeliningFlushState {

		INSTANCE;

		@Override
		public PipeliningFlushState newPipeline() {
			return INSTANCE;
		}

		@Override
		public void onOpen(StatefulConnection<?, ?> connection) {
			connection.setAutoFlushCommands(false);
		}

		@Override
		public void onCommand(StatefulConnection<?, ?> connection) {

		}

		@Override
		public void onClose(StatefulConnection<?, ?> connection) {
			connection.flushCommands();
			connection.setAutoFlushCommands(true);
		}
	}

	/**
	 * Pipeline state for buffered flushing.
	 *
	 * @author Mark Paluch
	 * @since 2.3
	 */
	private static class BufferedFlushing implements PipeliningFlushState {

		private final AtomicLong commands = new AtomicLong();

		private final int flushAfter;

		public BufferedFlushing(int flushAfter) {
			this.flushAfter = flushAfter;
		}

		@Override
		public void onOpen(StatefulConnection<?, ?> connection) {
			connection.setAutoFlushCommands(false);
		}

		@Override
		public void onCommand(StatefulConnection<?, ?> connection) {
			if (commands.incrementAndGet() % flushAfter == 0) {
				connection.flushCommands();
			}
		}

		@Override
		public void onClose(StatefulConnection<?, ?> connection) {
			connection.flushCommands();
			connection.setAutoFlushCommands(true);
		}
	}

	/**
	 * @since 2.3.8
	 */
	static class CustomCommandType implements ProtocolKeyword {

		private final String name;

		CustomCommandType(String name) {
			this.name = name;
		}

		@Override
		public byte[] getBytes() {
			return name.getBytes(StandardCharsets.US_ASCII);
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public boolean equals(@Nullable Object o) {

			if (this == o) {
				return true;
			}
			if (!(o instanceof CustomCommandType)) {
				return false;
			}
			CustomCommandType that = (CustomCommandType) o;
			return ObjectUtils.nullSafeEquals(name, that.name);
		}

		@Override
		public int hashCode() {
			return ObjectUtils.nullSafeHashCode(name);
		}

		@Override
		public String toString() {
			return name;
		}
	}
}
