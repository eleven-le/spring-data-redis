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

import static org.springframework.data.redis.connection.lettuce.LettuceConnection.*;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.ReadFrom;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.resource.ClientResources;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.ExceptionTranslationStrategy;
import org.springframework.data.redis.PassThroughExceptionTranslationStrategy;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.RedisConfiguration.ClusterConfiguration;
import org.springframework.data.redis.connection.RedisConfiguration.DomainSocketConfiguration;
import org.springframework.data.redis.connection.RedisConfiguration.WithDatabaseIndex;
import org.springframework.data.redis.connection.RedisConfiguration.WithPassword;
import org.springframework.data.util.Optionals;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Connection factory creating <a href="https://github.com/mp911de/lettuce">Lettuce</a>-based connections.
 * <p>
 * This factory creates a new {@link LettuceConnection} on each call to {@link #getConnection()}. Multiple
 * {@link LettuceConnection}s share a single thread-safe native connection by default.
 * <p>
 * The shared native connection is never closed by {@link LettuceConnection}, therefore it is not validated by default
 * on {@link #getConnection()}. Use {@link #setValidateConnection(boolean)} to change this behavior if necessary. Inject
 * a {@link Pool} to pool dedicated connections. If shareNativeConnection is true, the pool will be used to select a
 * connection for blocking and tx operations only, which should not share a connection. If native connection sharing is
 * disabled, the selected connection will be used for all operations.
 * <p>
 * {@link LettuceConnectionFactory} should be configured using an environmental configuration and the
 * {@link LettuceConnectionFactory client configuration}. Lettuce supports the following environmental configurations:
 * <ul>
 * <li>{@link RedisStandaloneConfiguration}</li>
 * <li>{@link RedisStaticMasterReplicaConfiguration}</li>
 * <li>{@link RedisSocketConfiguration}</li>
 * <li>{@link RedisSentinelConfiguration}</li>
 * <li>{@link RedisClusterConfiguration}</li>
 * </ul>
 * <p>
 * This connection factory must be {@link #afterPropertiesSet() initialized} prior to {@link #getConnection obtaining
 * connections}.
 *
 * @author Costin Leau
 * @author Jennifer Hickey
 * @author Thomas Darimont
 * @author Christoph Strobl
 * @author Mark Paluch
 * @author Balázs Németh
 * @author Ruben Cervilla
 * @author Luis De Bello
 * @author Andrea Como
 * @author Chris Bono
 */
public class LettuceConnectionFactory
		implements InitializingBean, DisposableBean, RedisConnectionFactory, ReactiveRedisConnectionFactory {

	private static final ExceptionTranslationStrategy EXCEPTION_TRANSLATION = new PassThroughExceptionTranslationStrategy(
			LettuceConverters.exceptionConverter());

	private final Log log = LogFactory.getLog(getClass());
	private final LettuceClientConfiguration clientConfiguration;

	private @Nullable AbstractRedisClient client;
	private @Nullable LettuceConnectionProvider connectionProvider;
	private @Nullable LettuceConnectionProvider reactiveConnectionProvider;
	private boolean validateConnection = false;
	private boolean shareNativeConnection = true;
	private boolean eagerInitialization = false;
	private @Nullable SharedConnection<byte[]> connection;
	private @Nullable SharedConnection<ByteBuffer> reactiveConnection;
	private @Nullable LettucePool pool;
	/** Synchronization monitor for the shared Connection */
	private final Object connectionMonitor = new Object();
	private boolean convertPipelineAndTxResults = true;

	private RedisStandaloneConfiguration standaloneConfig = new RedisStandaloneConfiguration("localhost", 6379);
	private PipeliningFlushPolicy pipeliningFlushPolicy = PipeliningFlushPolicy.flushEachCommand();

	private @Nullable RedisConfiguration configuration;

	private @Nullable ClusterCommandExecutor clusterCommandExecutor;

	private boolean initialized;
	private boolean destroyed;

	/**
	 * Constructs a new {@link LettuceConnectionFactory} instance with default settings.
	 */
	public LettuceConnectionFactory() {
		this(new MutableLettuceClientConfiguration());
	}

	/**
	 * Constructs a new {@link LettuceConnectionFactory} instance with default settings.
	 */
	public LettuceConnectionFactory(RedisStandaloneConfiguration configuration) {
		this(configuration, new MutableLettuceClientConfiguration());
	}

	/**
	 * Constructs a new {@link LettuceConnectionFactory} instance given {@link LettuceClientConfiguration}.
	 *
	 * @param clientConfig must not be {@literal null}
	 * @since 2.0
	 */
	private LettuceConnectionFactory(LettuceClientConfiguration clientConfig) {

		Assert.notNull(clientConfig, "LettuceClientConfiguration must not be null!");

		this.clientConfiguration = clientConfig;
		this.configuration = this.standaloneConfig;
	}

	/**
	 * Constructs a new {@link LettuceConnectionFactory} instance with default settings.
	 */
	public LettuceConnectionFactory(String host, int port) {
		this(new RedisStandaloneConfiguration(host, port), new MutableLettuceClientConfiguration());
	}

	/**
	 * Constructs a new {@link LettuceConnectionFactory} instance using the given {@link RedisSocketConfiguration}.
	 *
	 * @param redisConfiguration must not be {@literal null}.
	 * @since 2.1
	 */
	public LettuceConnectionFactory(RedisConfiguration redisConfiguration) {
		this(redisConfiguration, new MutableLettuceClientConfiguration());
	}

	/**
	 * Constructs a new {@link LettuceConnectionFactory} instance using the given {@link RedisSentinelConfiguration}.
	 *
	 * @param sentinelConfiguration must not be {@literal null}.
	 * @since 1.6
	 */
	public LettuceConnectionFactory(RedisSentinelConfiguration sentinelConfiguration) {
		this(sentinelConfiguration, new MutableLettuceClientConfiguration());
	}

	/**
	 * Constructs a new {@link LettuceConnectionFactory} instance using the given {@link RedisClusterConfiguration}
	 * applied to create a {@link RedisClusterClient}.
	 *
	 * @param clusterConfiguration must not be {@literal null}.
	 * @since 1.7
	 */
	public LettuceConnectionFactory(RedisClusterConfiguration clusterConfiguration) {
		this(clusterConfiguration, new MutableLettuceClientConfiguration());
	}

	/**
	 * @param pool
	 * @deprecated since 2.0, use pooling via {@link LettucePoolingClientConfiguration}.
	 */
	@Deprecated
	public LettuceConnectionFactory(LettucePool pool) {

		this(new MutableLettuceClientConfiguration());
		this.pool = pool;
	}

	/**
	 * Constructs a new {@link LettuceConnectionFactory} instance using the given {@link RedisStandaloneConfiguration} and
	 * {@link LettuceClientConfiguration}.
	 *
	 * @param standaloneConfig must not be {@literal null}.
	 * @param clientConfig must not be {@literal null}.
	 * @since 2.0
	 */
	public LettuceConnectionFactory(RedisStandaloneConfiguration standaloneConfig,
			LettuceClientConfiguration clientConfig) {

		this(clientConfig);

		Assert.notNull(standaloneConfig, "RedisStandaloneConfiguration must not be null!");

		this.standaloneConfig = standaloneConfig;
		this.configuration = this.standaloneConfig;
	}

	/**
	 * Constructs a new {@link LettuceConnectionFactory} instance using the given
	 * {@link RedisStaticMasterReplicaConfiguration} and {@link LettuceClientConfiguration}.
	 *
	 * @param redisConfiguration must not be {@literal null}.
	 * @param clientConfig must not be {@literal null}.
	 * @since 2.1
	 */
	public LettuceConnectionFactory(RedisConfiguration redisConfiguration, LettuceClientConfiguration clientConfig) {

		this(clientConfig);

		Assert.notNull(redisConfiguration, "RedisConfiguration must not be null!");

		this.configuration = redisConfiguration;
	}

	/**
	 * Constructs a new {@link LettuceConnectionFactory} instance using the given {@link RedisSentinelConfiguration} and
	 * {@link LettuceClientConfiguration}.
	 *
	 * @param sentinelConfiguration must not be {@literal null}.
	 * @param clientConfig must not be {@literal null}.
	 * @since 2.0
	 */
	public LettuceConnectionFactory(RedisSentinelConfiguration sentinelConfiguration,
			LettuceClientConfiguration clientConfig) {

		this(clientConfig);

		Assert.notNull(sentinelConfiguration, "RedisSentinelConfiguration must not be null!");

		this.configuration = sentinelConfiguration;
	}

	/**
	 * Constructs a new {@link LettuceConnectionFactory} instance using the given {@link RedisClusterConfiguration} and
	 * {@link LettuceClientConfiguration}.
	 *
	 * @param clusterConfiguration must not be {@literal null}.
	 * @param clientConfig must not be {@literal null}.
	 * @since 2.0
	 */
	public LettuceConnectionFactory(RedisClusterConfiguration clusterConfiguration,
			LettuceClientConfiguration clientConfig) {

		this(clientConfig);

		Assert.notNull(clusterConfiguration, "RedisClusterConfiguration must not be null!");

		this.configuration = clusterConfiguration;
	}

	/**
	 * Creates a {@link RedisConfiguration} based on a {@link String URI} according to the following:
	 * <ul>
	 * <li>If {@code redisUri} contains sentinels, a {@link RedisSentinelConfiguration} is returned</li>
	 * <li>If {@code redisUri} has a configured socket a {@link RedisSocketConfiguration} is returned</li>
	 * <li>Otherwise a {@link RedisStandaloneConfiguration} is returned</li>
	 * </ul>
	 *
	 * @param redisUri the connection URI in the format of a {@link RedisURI}.
	 * @return an appropriate {@link RedisConfiguration} instance representing the Redis URI.
	 * @since 2.5.3
	 * @see RedisURI
	 */
	public static RedisConfiguration createRedisConfiguration(String redisUri) {

		Assert.hasText(redisUri, "RedisURI must not be null and not empty");

		return createRedisConfiguration(RedisURI.create(redisUri));
	}

	/**
	 * Creates a {@link RedisConfiguration} based on a {@link RedisURI} according to the following:
	 * <ul>
	 * <li>If {@link RedisURI} contains sentinels, a {@link RedisSentinelConfiguration} is returned</li>
	 * <li>If {@link RedisURI} has a configured socket a {@link RedisSocketConfiguration} is returned</li>
	 * <li>Otherwise a {@link RedisStandaloneConfiguration} is returned</li>
	 * </ul>
	 *
	 * @param redisUri the connection URI.
	 * @return an appropriate {@link RedisConfiguration} instance representing the Redis URI.
	 * @since 2.5.3
	 * @see RedisURI
	 */
	public static RedisConfiguration createRedisConfiguration(RedisURI redisUri) {

		Assert.notNull(redisUri, "RedisURI must not be null");

		if (!ObjectUtils.isEmpty(redisUri.getSentinels())) {
			return LettuceConverters.createRedisSentinelConfiguration(redisUri);
		}

		if (!ObjectUtils.isEmpty(redisUri.getSocket())) {
			return LettuceConverters.createRedisSocketConfiguration(redisUri);
		}

		return LettuceConverters.createRedisStandaloneConfiguration(redisUri);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
	 */
	/**
	 * LettuceConnectionFactory 的初始化准备阶段。
	 * <h3>1. 背景概念（小白科普）</h3>
	 * <p>想象你开了一家“Redis奶茶店”（LettuceConnectionFactory）。
	 * 在正式开门营业（提供连接给业务代码用）之前，你必须先完成一系列准备工作：
	 * 比如买好制冰机（创建客户端）、招聘好调饮师（创建连接提供者）、确认分店地址（集群配置）等。</p>
	 * <p>在 Spring 框架中，<code>afterPropertiesSet()</code> 就是这个<b>“开门前的准备工作”</b>阶段。
	 * 它来自 Spring 的 <code>InitializingBean</code> 接口。当 Spring 容器把所有的配置项（如 Redis 的 IP、端口、密码、超时时间等）都注入到这个类之后，Spring 会自动调用这个方法，完成核心网络组件的初始化。</p>
	 * * <h3>2. 核心目标</h3>
	 * <p>确保当业务代码（比如你的 Service 层）真正去调用 <code>getConnection()</code> 获取 Redis 连接时，底层的 Lettuce 客户端、异常转换器、集群路由等都已经整装待发。
	 * 如果跳过这步，直接拿连接就会引发未初始化的错误。</p>
	 * * <h3>💡 部署建议与避坑指南 (架构师视角)</h3>
	 * <ul>
	 * <li><b>抛弃 Jedis 思维，不要滥用连接池：</b>
	 * 很多从 Jedis 迁移过来的开发者第一反应是配置庞大的连接池。但 Lettuce 基于 Netty 设计，天生支持异步非阻塞的多路复用。这意味着几百个并发线程可以完美复用同一个物理连接（<code>shareNativeConnection = true</code>）。除非大量依赖事务或阻塞式命令（如 BLPOP），否则推荐单连接共享模型，性能更高且极度节省资源。</li>
	 * <li><b>响应式编程（WebFlux）强烈建议开启连接预热：</b>
	 * 默认的懒加载机制会导致首个请求处理时抛出类似 <code>block() is not supported in thread reactor-http-nio</code> 的严重异常。务必通过 <code>factory.setEagerInitialization(true)</code> 开启预热，让应用启动阶段就打通 TCP 通道。</li>
	 * <li><b>捕获异常需认准 Spring 体系：</b>
	 * 得益于下方的异常翻译机制，业务层 try-catch 时请捕获 Spring 的 <code>DataAccessException</code> 及其子类，切勿去抓 Lettuce 原生的 <code>RedisException</code>，因为后者已经被包装和替换了。</li>
	 * </ul>
	 *
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
	 */
	public void afterPropertiesSet() {
		// 【步骤 1：创建底层客户端引擎】
		// 根据配置(单机/哨兵/集群) new 一个底层的 RedisClient 或 RedisClusterClient，这是与 Redis 服务器进行 TCP 通信的“物理引擎”。
		this.client = createClient();

		// 【步骤 2：创建并包装连接提供者（同步/响应式）】
		// 1. 决定用连接池（Pool）还是普通连接。
		// 2. 包装者模式：屏蔽底层细节做“异常翻译”，将 Lettuce 原生的 RedisConnectionException 统一转为 Spring 的 DataAccessException 体系，这样在业务层 try-catch 不用关心底层用的是 Redis 还是 MySQL。
		this.connectionProvider = new ExceptionTranslatingConnectionProvider(createConnectionProvider(client, CODEC));
		// 同理，为响应式（Reactive / WebFlux）编程准备一套异常翻译连接提供者。
		this.reactiveConnectionProvider = new ExceptionTranslatingConnectionProvider(
				createConnectionProvider(client, LettuceReactiveRedisConnection.CODEC));

		// 【步骤 3：如果是 Redis 集群（Cluster）架构的专属逻辑】
		if (isClusterAware()) {
			// Redis 集群模式下，数据（Slot）是分布在不同的物理节点上的。 ClusterCommandExecutor 充当“智能路由器”。
			// 结合 TopologyProvider（感知哪个节点活着、哪个槽位在哪个节点上），通过计算 CRC16 负责把命令精确投递到 key 所在的物理节点上。
			this.clusterCommandExecutor = new ClusterCommandExecutor(
					new LettuceClusterTopologyProvider((RedisClusterClient) client),
					new LettuceClusterConnection.LettuceClusterNodeResourceProvider(this.connectionProvider),
					EXCEPTION_TRANSLATION);
		}

		// 【步骤 4：挂上“正常营业”的牌子】
		// 标记状态为 true。后续 getConnection() 首行会执行 assertInitialized()，防止拿到未初始化好的工厂。
		this.initialized = true;

		// 【步骤 5：连接预热（非常重要的高级特性）】
		// Lettuce 默认多线程共享同一个底层的物理 TCP 连接（shareNativeConnection = true），默认情况下，这个连接是“懒加载”的（即第一个人来发命令时，才去真正建立 TCP 三次握手）。
		// 为防止响应式 Reactive 应用首次连接阻塞主线程，若开启 eagerInitialization = true，则在启动阶段提前建好 TCP 连接。
		if (getEagerInitialization() && getShareNativeConnection()) {
			initConnection();
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.beans.factory.DisposableBean#destroy()
	 */
	public void destroy() {

		resetConnection();

		if (clusterCommandExecutor != null) {

			try {
				clusterCommandExecutor.destroy();
			} catch (Exception ex) {
				log.warn("Cannot properly close cluster command executor", ex);
			}
		}

		dispose(connectionProvider);
		dispose(reactiveConnectionProvider);

		try {
			Duration quietPeriod = clientConfiguration.getShutdownQuietPeriod();
			Duration timeout = clientConfiguration.getShutdownTimeout();
			client.shutdown(quietPeriod.toMillis(), timeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (Exception e) {

			if (log.isWarnEnabled()) {
				log.warn((client != null ? ClassUtils.getShortName(client.getClass()) : "LettuceClient")
						+ " did not shut down gracefully.", e);
			}
		}

		this.destroyed = true;
	}

	private void dispose(LettuceConnectionProvider connectionProvider) {

		if (connectionProvider instanceof DisposableBean) {
			try {
				((DisposableBean) connectionProvider).destroy();
			} catch (Exception e) {

				if (log.isWarnEnabled()) {
					log.warn(connectionProvider + " did not shut down gracefully.", e);
				}
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnectionFactory#getConnection()
	 */
	/**
	 * 看了那么多初始化的准备工作，终于来到了**“交付提车”**的环节。
	 * getConnection() 是整个 LettuceConnectionFactory 最核心的、对外暴露的公有 API。当你在业务代码里使用 RedisTemplate.opsForValue().get("key") 时，Spring 底层的第一步，就是跑到这里来大喊一声：“给我一个可以发命令的 Redis 连接！”
	 * 从架构上看，这个方法的作用是把底层的 Lettuce 原生对象，**包装/适配（Adapter Pattern）**成 Spring Data Redis 统一的标准接口 RedisConnection，然后交出去。
	 *
	 * 这段代码非常精炼，但里面藏着 Spring Data Redis 最精华的**“双擎驱动”**设计机制：
	 * @return
	 */
	/**
	 * 获取与 Redis 服务器通信的连接对象。
	 * <hr>
	 * <h3>1. 核心定位：“交付提车”环节</h3>
	 * <p><code>getConnection()</code> 是整个 <code>LettuceConnectionFactory</code> 最核心的、对外暴露的公有 API。
	 * 当业务代码使用 <code>RedisTemplate.opsForValue().get("key")</code> 时，底层第一步就是来这里获取连接。</p>
	 * <p>从架构上看，它使用了<b>适配器模式（Adapter Pattern）</b>，将底层的 Lettuce 原生对象包装/适配成 Spring Data Redis 统一的标准接口 <code>RedisConnection</code> 然后交出去。</p>
	 * * <h3>2. 【核心高能】双擎驱动的包装器</h3>
	 * <p>返回的 <code>LettuceConnection</code> 其实是一个<b>“智能代理”</b>。它内部同时持有了两台发动机：</p>
	 * <ul>
	 * <li><b>一号发动机（SharedConnection）：</b>大家都在共享的全局唯一物理 TCP 连接（极其高效，只要 shareNativeConnection = true）。</li>
	 * <li><b>二号发动机（ConnectionProvider）：</b>备用提车点（可能是连接池，也可能是新建物理连接的工厂）。</li>
	 * </ul>
	 * <p><b>智能调度逻辑：</b>当上层业务拿着 connection 执行普通 GET/SET 时，代理会悄悄把命令塞进“一号发动机”发出去，速度极快，不占额外资源。
	 * 当突然执行 <code>MULTI</code>（事务）或 <code>BLPOP</code>（阻塞）等会独占通道的命令时，代理瞬间察觉，立刻转向“二号发动机”借出一个全新的专属物理连接来处理，用完即还。</p>
	 * * <h3>💡 部署建议：性能优化的迷思与架构级真相</h3>
	 * <blockquote>
	 * <b>迷思：</b>我每次调 RedisTemplate 都在调用 getConnection()，这不是每次都在新建物理连接吗？太慢了吧！<br><br>
	 * <b>架构级真相：完全不用担心！</b>每次调用返回的 <code>LettuceConnection</code> 确实是 new 出来的新 Java 对象，但它仅仅是一个<b>几字节的轻量级包装壳（Wrapper）</b>。<br>
	 * 它内部包裹的那个真正干活的物理 TCP 通道（getSharedConnection()），在整个 JVM 生命周期里永远只有那一个（未发生网络断连的情况下）。<br>
	 * 所以，你可以疯狂高并发地调用 RedisTemplate，根本不会产生任何创建物理连接的开销！这正是<b>异步非阻塞多路复用（Multiplexing）</b>带来的降维性能打击。
	 * </blockquote>
	 *
	 * @return 包装适配好的 Spring 标准 RedisConnection
	 */
	public RedisConnection getConnection() {
		// 【步骤 1：营业前的安全检查】
		// 检查 initialized 变量。防止使用反射等手段跳过 Spring 生命周期，拿到半成品工厂导致线上雪崩。
		assertInitialized();

		// 【步骤 2：集群架构的特殊通道】
		// 单机版与集群版的连接对象在底层 API 和路由逻辑上天差地别（必须处理重定向等）。虽被上层接口抹平，但实例化必须分道扬镳。
		if (isClusterAware()) {
			return getClusterConnection();
		}

		// 【步骤 3：实例化双擎驱动包装器】
		// 将“共享连接(SharedConnection)”和“连接提供者(ConnectionProvider)”同时注入。
		// 赋予该 connection 根据命令类型（普通 vs 阻塞/事务）智能切换物理通道的能力。
		LettuceConnection connection;
		connection = doCreateLettuceConnection(getSharedConnection(), connectionProvider, getTimeout(), getDatabase());
		// 【步骤 4：统一流水线标准】
		// 打开转换开关：强制将 Lettuce 原生驱动返回的杂乱无章的 Pipeline（管道）和 Transaction（事务）结果，统统拼装成 Spring 规定的标准 List 格式。
		connection.setConvertPipelineAndTxResults(convertPipelineAndTxResults);
		return connection;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnectionFactory#getClusterConnection()
	 */
	@Override
	public RedisClusterConnection getClusterConnection() {

		assertInitialized();

		if (!isClusterAware()) {
			throw new InvalidDataAccessApiUsageException("Cluster is not configured!");
		}

		RedisClusterClient clusterClient = (RedisClusterClient) client;

		StatefulRedisClusterConnection<byte[], byte[]> sharedConnection = getSharedClusterConnection();

		LettuceClusterTopologyProvider topologyProvider = new LettuceClusterTopologyProvider(clusterClient);
		return doCreateLettuceClusterConnection(sharedConnection, connectionProvider, topologyProvider,
				clusterCommandExecutor, clientConfiguration.getCommandTimeout());
	}

	/**
	 * <h3>1. 架构设计模式：模板方法与钩子 (Hook)</h3>
	 * <p>在 Spring 源码中，但凡看到以 <code>do</code> 开头且受 <code>protected</code> 修饰的方法（如 doCreateBean, doDispatch），它一定是个<b>模板方法（Template Method）里的“钩子”</b>。
	 * 其核心作用有二：一是真正执行实例化对象的脏活累活；二是给未来的开发者留一道后门，方便进行框架级别的扩展。</p>
	 *
	 * <h3>2. 核心轻量动作：组装“双擎代理”外壳</h3>
	 * <p>印证了上一步的结论：每次 <code>getConnection()</code> 只是在 JVM 堆内存里 <code>new</code> 了一个普普通通的 <code>LettuceConnection</code> 包装对象，将“一号发动机(共享连接)”与“二号发动机(备用连接池)”同时塞入。
	 * <b>这里既没有发生昂贵的 TCP 握手，也没有发出真实的 IO 请求，耗时在纳秒级，对系统毫无压力。</b></p>
	 *
	 * <h3>3. 性能调优：注入管道刷盘策略 (Pipelining Flush Policy)</h3>
	 * <p><b>什么是 Pipelining（管道）？</b>当需要向 Redis 插入一万条数据时，不按顺序等回复，而是客户端疯狂连发，最后统一收割结果，极大节省网络 RTT 耗时。<br>
	 * <b>策略抉择：</b>在疯狂发命令时，底层 Netty 是发一条命令就向网卡（TCP Buffer）<code>flush</code> 一次（默认配置）？还是先把一万个命令暂存内存最后统一 <code>flush</code>？此处注入的策略决定了极限批量写入性能的上限。</p>
	 * 默认情况下，它是 flushEachCommand()（发一条刷一条）。如果你在追逐极限的批量写入性能，可以调整这个策略，改为批量打包刷盘，进一步压榨网卡 I/O。
	 * <h3>💡 部署建议：高级架构师的扩展玩法 (监控与全链路追踪)</h3>
	 * <blockquote>
	 * <b>实战场景：</b>如果公司有全链路追踪（SkyWalking、Zipkin）或变态的安全审计需求，想要监控所有 Redis 命令耗时，怎么做？<br>
	 * <b>解决方案：</b>利用这个 <code>doCreateLettuceConnection</code> 钩子方法！这就是开源框架“对内封闭，对外扩展”的魅力。
	 * <pre><code>
	 * public class MyTracingLettuceConnectionFactory extends LettuceConnectionFactory {
	 * &#64;Override
	 * protected LettuceConnection doCreateLettuceConnection(...) {
	 * // 1. 先调父类方法拿到原生的连接包装壳
	 * LettuceConnection originalConn = super.doCreateLettuceConnection(sharedConnection, connectionProvider, timeout, database);
	 * // 2. 偷天换日：用你自己的代理类包裹它，植入计时打点逻辑，然后返回！
	 * return new TracingLettuceConnectionProxy(originalConn);
	 * }
	 * }
	 * </code></pre>
	 * </blockquote>
	 * <hr>
	 * Customization hook for {@link LettuceConnection} creation.
	 *
	 * @param sharedConnection the shared {@link StatefulRedisConnection} if {@link #getShareNativeConnection()} is
	 *          {@literal true}; {@literal null} otherwise.
	 * @param connectionProvider the {@link LettuceConnectionProvider} to release connections.
	 * @param timeout command timeout in {@link TimeUnit#MILLISECONDS}.
	 * @param database database index to operate on.
	 * @return the {@link LettuceConnection}.
	 * @throws IllegalArgumentException if a required parameter is {@literal null}.
	 * @since 2.2
	 */
	protected LettuceConnection doCreateLettuceConnection(
			@Nullable StatefulRedisConnection<byte[], byte[]> sharedConnection, LettuceConnectionProvider connectionProvider,
			long timeout, int database) {
		// 【步骤 1：组装“双擎代理”外壳】
		// 纯内存实例化对象，极其轻量级。将全局共享 TCP 通道与专属连接提供者合并封装。
		LettuceConnection connection = new LettuceConnection(sharedConnection, connectionProvider, timeout, database);
		// 【步骤 2：注入“管道刷盘策略”】
		// 决定底层 Netty 在处理 Pipeline 批量命令时的 I/O flush 频率。
		connection.setPipeliningFlushPolicy(this.pipeliningFlushPolicy);

		return connection;
	}

	/**
	 * Customization hook for {@link LettuceClusterConnection} creation.
	 *
	 * @param sharedConnection the shared {@link StatefulRedisConnection} if {@link #getShareNativeConnection()} is
	 *          {@literal true}; {@literal null} otherwise.
	 * @param connectionProvider the {@link LettuceConnectionProvider} to release connections.
	 * @param topologyProvider the {@link ClusterTopologyProvider}.
	 * @param clusterCommandExecutor the {@link ClusterCommandExecutor} to release connections.
	 * @param commandTimeout command timeout {@link Duration}.
	 * @return the {@link LettuceConnection}.
	 * @throws IllegalArgumentException if a required parameter is {@literal null}.
	 * @since 2.2
	 */
	protected LettuceClusterConnection doCreateLettuceClusterConnection(
			@Nullable StatefulRedisClusterConnection<byte[], byte[]> sharedConnection,
			LettuceConnectionProvider connectionProvider, ClusterTopologyProvider topologyProvider,
			ClusterCommandExecutor clusterCommandExecutor, Duration commandTimeout) {

		LettuceClusterConnection connection = new LettuceClusterConnection(sharedConnection, connectionProvider,
				topologyProvider, clusterCommandExecutor, commandTimeout);
		connection.setPipeliningFlushPolicy(this.pipeliningFlushPolicy);

		return connection;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.ReactiveRedisConnectionFactory#getReactiveConnection()
	 */
	@Override
	public LettuceReactiveRedisConnection getReactiveConnection() {

		assertInitialized();

		if (isClusterAware()) {
			return getReactiveClusterConnection();
		}

		return getShareNativeConnection()
				? new LettuceReactiveRedisConnection(getSharedReactiveConnection(), reactiveConnectionProvider)
				: new LettuceReactiveRedisConnection(reactiveConnectionProvider);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.ReactiveRedisConnectionFactory#getReactiveClusterConnection()
	 */
	@Override
	public LettuceReactiveRedisClusterConnection getReactiveClusterConnection() {

		assertInitialized();

		if (!isClusterAware()) {
			throw new InvalidDataAccessApiUsageException("Cluster is not configured!");
		}

		RedisClusterClient client = (RedisClusterClient) this.client;

		return getShareNativeConnection()
				? new LettuceReactiveRedisClusterConnection(getSharedReactiveConnection(), reactiveConnectionProvider, client)
				: new LettuceReactiveRedisClusterConnection(reactiveConnectionProvider, client);
	}

	/**
	 * Initialize the shared connection if {@link #getShareNativeConnection() native connection sharing} is enabled and
	 * reset any previously existing connection.
	 */
	public void initConnection() {

		resetConnection();

		if (isClusterAware()) {
			getSharedClusterConnection();
		} else {
			getSharedConnection();
		}

		getSharedReactiveConnection();
	}

	/**
	 * Reset the underlying shared Connection, to be reinitialized on next access.
	 */
	public void resetConnection() {

		assertInitialized();

		Optionals.toStream(Optional.ofNullable(connection), Optional.ofNullable(reactiveConnection))
				.forEach(SharedConnection::resetConnection);

		synchronized (this.connectionMonitor) {

			this.connection = null;
			this.reactiveConnection = null;
		}
	}

	/**
	 * Validate the shared connections and reinitialize if invalid.
	 */
	public void validateConnection() {

		assertInitialized();

		getOrCreateSharedConnection().validateConnection();
		getOrCreateSharedReactiveConnection().validateConnection();
	}

	private SharedConnection<byte[]> getOrCreateSharedConnection() {

		synchronized (this.connectionMonitor) {

			if (this.connection == null) {
				this.connection = new SharedConnection<>(connectionProvider);
			}

			return this.connection;
		}
	}

	private SharedConnection<ByteBuffer> getOrCreateSharedReactiveConnection() {

		synchronized (this.connectionMonitor) {

			if (this.reactiveConnection == null) {
				this.reactiveConnection = new SharedConnection<>(reactiveConnectionProvider);
			}

			return this.reactiveConnection;
		}
	}

	public DataAccessException translateExceptionIfPossible(RuntimeException ex) {
		return EXCEPTION_TRANSLATION.translate(ex);
	}

	/**
	 * Returns the current host.
	 *
	 * @return the host.
	 */
	public String getHostName() {
		return RedisConfiguration.getHostOrElse(configuration, standaloneConfig::getHostName);
	}

	/**
	 * Sets the hostname.
	 *
	 * @param hostName the hostname to set.
	 * @deprecated since 2.0, configure the hostname using {@link RedisStandaloneConfiguration}.
	 */
	@Deprecated
	public void setHostName(String hostName) {
		standaloneConfig.setHostName(hostName);
	}

	/**
	 * Returns the current port.
	 *
	 * @return the port.
	 */
	public int getPort() {
		return RedisConfiguration.getPortOrElse(configuration, standaloneConfig::getPort);
	}

	/**
	 * Sets the port.
	 *
	 * @param port the port to set.
	 * @deprecated since 2.0, configure the port using {@link RedisStandaloneConfiguration}.
	 */
	@Deprecated
	public void setPort(int port) {
		standaloneConfig.setPort(port);
	}

	/**
	 * Configures the flushing policy when using pipelining. If not set, defaults to
	 * {@link PipeliningFlushPolicy#flushEachCommand() flush on each command}.
	 *
	 * @param pipeliningFlushPolicy the flushing policy to control when commands get written to the Redis connection.
	 * @see LettuceConnection#openPipeline()
	 * @see StatefulRedisConnection#flushCommands()
	 * @since 2.3
	 */
	public void setPipeliningFlushPolicy(PipeliningFlushPolicy pipeliningFlushPolicy) {

		Assert.notNull(pipeliningFlushPolicy, "PipeliningFlushingPolicy must not be null!");

		this.pipeliningFlushPolicy = pipeliningFlushPolicy;
	}

	/**
	 * Returns the connection timeout (in milliseconds).
	 *
	 * @return connection timeout.
	 */
	public long getTimeout() {
		return getClientTimeout();
	}

	/**
	 * Sets the connection timeout (in milliseconds).
	 *
	 * @param timeout the timeout.
	 * @deprecated since 2.0, configure the timeout using {@link LettuceClientConfiguration}.
	 * @throws IllegalStateException if {@link LettuceClientConfiguration} is immutable.
	 */
	@Deprecated
	public void setTimeout(long timeout) {
		getMutableConfiguration().setTimeout(Duration.ofMillis(timeout));
	}

	/**
	 * Returns whether to use SSL.
	 *
	 * @return use of SSL.
	 */
	public boolean isUseSsl() {
		return clientConfiguration.isUseSsl();
	}

	/**
	 * Sets to use SSL connection.
	 *
	 * @param useSsl {@literal true} to use SSL.
	 * @deprecated since 2.0, configure SSL usage using {@link LettuceClientConfiguration}.
	 * @throws IllegalStateException if {@link LettuceClientConfiguration} is immutable.
	 */
	@Deprecated
	public void setUseSsl(boolean useSsl) {
		getMutableConfiguration().setUseSsl(useSsl);
	}

	/**
	 * Returns whether to verify certificate validity/hostname check when SSL is used.
	 *
	 * @return whether to verify peers when using SSL.
	 */
	public boolean isVerifyPeer() {
		return clientConfiguration.isVerifyPeer();
	}

	/**
	 * Sets to use verify certificate validity/hostname check when SSL is used.
	 *
	 * @param verifyPeer {@literal false} not to verify hostname.
	 * @deprecated since 2.0, configure peer verification using {@link LettuceClientConfiguration}.
	 * @throws IllegalStateException if {@link LettuceClientConfiguration} is immutable.
	 */
	@Deprecated
	public void setVerifyPeer(boolean verifyPeer) {
		getMutableConfiguration().setVerifyPeer(verifyPeer);
	}

	/**
	 * Returns whether to issue a StartTLS.
	 *
	 * @return use of StartTLS.
	 */
	public boolean isStartTls() {
		return clientConfiguration.isStartTls();
	}

	/**
	 * Sets to issue StartTLS.
	 *
	 * @param startTls {@literal true} to issue StartTLS.
	 * @deprecated since 2.0, configure StartTLS using {@link LettuceClientConfiguration}.
	 * @throws IllegalStateException if {@link LettuceClientConfiguration} is immutable.
	 */
	@Deprecated
	public void setStartTls(boolean startTls) {
		getMutableConfiguration().setStartTls(startTls);
	}

	/**
	 * Indicates if validation of the native Lettuce connection is enabled.
	 *
	 * @return connection validation enabled.
	 */
	public boolean getValidateConnection() {
		return validateConnection;
	}

	/**
	 * Enables validation of the shared native Lettuce connection on calls to {@link #getConnection()}. A new connection
	 * will be created and used if validation fails.
	 * <p>
	 * Lettuce will automatically reconnect until close is called, which should never happen through
	 * {@link LettuceConnection} if a shared native connection is used, therefore the default is {@literal false}.
	 * <p>
	 * Setting this to {@literal true} will result in a round-trip call to the server on each new connection, so this
	 * setting should only be used if connection sharing is enabled and there is code that is actively closing the native
	 * Lettuce connection.
	 *
	 * @param validateConnection enable connection validation.
	 */
	public void setValidateConnection(boolean validateConnection) {
		this.validateConnection = validateConnection;
	}

	/**
	 * Indicates if multiple {@link LettuceConnection}s should share a single native connection.
	 *
	 * @return native connection shared.
	 */
	public boolean getShareNativeConnection() {
		return shareNativeConnection;
	}

	/**
	 * Enables multiple {@link LettuceConnection}s to share a single native connection. If set to {@literal false}, every
	 * operation on {@link LettuceConnection} will open and close a socket.
	 *
	 * @param shareNativeConnection enable connection sharing.
	 */
	public void setShareNativeConnection(boolean shareNativeConnection) {
		/* ===== L3-05 讲解注释 BY 军火库 =====
		 * <h3>🔥 配置开关 · 是否注入共享原生连接</h3>
		 *
		 * <p><b>调用方</b>：Spring Boot 配置绑定、自定义 {@code LettuceConnectionFactory}
		 * Bean，或本章 {@code ForceDedicatedConnectionDemo} 手工设置。</p>
		 *
		 * <p><b>触发条件</b>：业务希望关闭 Lettuce 默认共享原生连接，让每个
		 * {@code LettuceConnection} 的普通命令也通过 {@code LettuceConnectionProvider}
		 * 获取连接。</p>
		 *
		 * <p><b>做出的决定</b>：只改变 Factory 后续创建连接时是否传入 shared connection。
		 * 它不是“事务/订阅/阻塞命令开关”，也不代表 Redis 协议要求独占；它改变的是默认连接策略。</p>
		 *
		 * <p><b>跳过它会怎样</b>：如果误以为 {@code false} 等价于“只让特殊命令走专用连接”，
		 * 会在商品详情、SKU 价格查询这类普通短命令上也制造大量连接获取/释放成本。
		 * ===== END ===== */
		this.shareNativeConnection = shareNativeConnection;
	}

	/**
	 * Indicates {@link #setShareNativeConnection(boolean) shared connections} should be eagerly initialized. Eager
	 * initialization requires a running Redis instance during application startup to allow early validation of connection
	 * factory configuration. Eager initialization also prevents blocking connect while using reactive API and is
	 * recommended for reactive API usage.
	 *
	 * @return {@link true} if the shared connection is initialized upon {@link #afterPropertiesSet()}.
	 * @since 2.2
	 */
	public boolean getEagerInitialization() {
		return eagerInitialization;
	}

	/**
	 * Enables eager initialization of {@link #setShareNativeConnection(boolean) shared connections}.
	 *
	 * @param eagerInitialization enable eager connection shared connection initialization upon
	 *          {@link #afterPropertiesSet()}.
	 * @since 2.2
	 */
	public void setEagerInitialization(boolean eagerInitialization) {
		this.eagerInitialization = eagerInitialization;
	}

	/**
	 * Returns the index of the database.
	 *
	 * @return the database index.
	 */
	public int getDatabase() {
		return RedisConfiguration.getDatabaseOrElse(configuration, standaloneConfig::getDatabase);
	}

	/**
	 * Sets the index of the database used by this connection factory. Default is 0.
	 *
	 * @param index database index
	 */
	public void setDatabase(int index) {

		Assert.isTrue(index >= 0, "invalid DB index (a positive index required)");

		if (RedisConfiguration.isDatabaseIndexAware(configuration)) {

			((WithDatabaseIndex) configuration).setDatabase(index);
			return;
		}

		standaloneConfig.setDatabase(index);
	}

	/**
	 * Returns the client name.
	 *
	 * @return the client name or {@literal null} if not set.
	 * @since 2.1
	 */
	@Nullable
	public String getClientName() {
		return clientConfiguration.getClientName().orElse(null);
	}

	/**
	 * Sets the client name used by this connection factory.
	 *
	 * @param clientName the client name. Can be {@literal null}.
	 * @since 2.1
	 * @deprecated configure the client name using {@link LettuceClientConfiguration}.
	 * @throws IllegalStateException if {@link LettuceClientConfiguration} is immutable.
	 */
	@Deprecated
	public void setClientName(@Nullable String clientName) {
		this.getMutableConfiguration().setClientName(clientName);
	}

	/**
	 * Returns the native {@link AbstractRedisClient} used by this instance. The client is initialized as part of
	 * {@link #afterPropertiesSet() the bean initialization lifecycle} and only available when this connection factory is
	 * initialized.
	 * <p>
	 * Depending on the configuration, the client can be either {@link RedisClient} or {@link RedisClusterClient}.
	 *
	 * @return the native {@link AbstractRedisClient}. Can be {@literal null} if not initialized.
	 * @since 2.5
	 * @see #afterPropertiesSet()
	 */
	@Nullable
	public AbstractRedisClient getNativeClient() {
		assertInitialized();
		return this.client;
	}

	/**
	 * Returns the native {@link AbstractRedisClient} used by this instance. The client is initialized as part of
	 * {@link #afterPropertiesSet() the bean initialization lifecycle} and only available when this connection factory is
	 * initialized. Throws {@link IllegalStateException} if not yet initialized.
	 * <p>
	 * Depending on the configuration, the client can be either {@link RedisClient} or {@link RedisClusterClient}.
	 *
	 * @return the native {@link AbstractRedisClient}.
	 * @since 2.5
	 * @throws IllegalStateException if not yet initialized.
	 * @see #getNativeClient()
	 */
	public AbstractRedisClient getRequiredNativeClient() {

		AbstractRedisClient client = getNativeClient();

		Assert.state(client != null, "Client not yet initialized. Did you forget to call initialize the bean?");

		return client;
	}

	@Nullable
	private String getRedisUsername() {
		return RedisConfiguration.getUsernameOrElse(configuration, standaloneConfig::getUsername);
	}

	/**
	 * Returns the password used for authenticating with the Redis server.
	 *
	 * @return password for authentication or {@literal null} if not set.
	 */
	@Nullable
	public String getPassword() {
		return getRedisPassword().map(String::new).orElse(null);
	}

	private RedisPassword getRedisPassword() {
		return RedisConfiguration.getPasswordOrElse(configuration, standaloneConfig::getPassword);
	}

	/**
	 * Sets the password used for authenticating with the Redis server.
	 *
	 * @param password the password to set
	 * @deprecated since 2.0, configure the password using {@link RedisStandaloneConfiguration},
	 *             {@link RedisSentinelConfiguration} or {@link RedisClusterConfiguration}.
	 */
	@Deprecated
	public void setPassword(String password) {

		if (RedisConfiguration.isAuthenticationAware(configuration)) {

			((WithPassword) configuration).setPassword(password);
			return;
		}

		standaloneConfig.setPassword(RedisPassword.of(password));
	}

	/**
	 * Returns the shutdown timeout for shutting down the RedisClient (in milliseconds).
	 *
	 * @return shutdown timeout.
	 * @since 1.6
	 */
	public long getShutdownTimeout() {
		return clientConfiguration.getShutdownTimeout().toMillis();
	}

	/**
	 * Sets the shutdown timeout for shutting down the RedisClient (in milliseconds).
	 *
	 * @param shutdownTimeout the shutdown timeout.
	 * @since 1.6
	 * @deprecated since 2.0, configure the shutdown timeout using {@link LettuceClientConfiguration}.
	 * @throws IllegalStateException if {@link LettuceClientConfiguration} is immutable.
	 */
	@Deprecated
	public void setShutdownTimeout(long shutdownTimeout) {
		getMutableConfiguration().setShutdownTimeout(Duration.ofMillis(shutdownTimeout));
	}

	/**
	 * Get the {@link ClientResources} to reuse infrastructure.
	 *
	 * @return {@literal null} if not set.
	 * @since 1.7
	 */
	public ClientResources getClientResources() {
		return clientConfiguration.getClientResources().orElse(null);
	}

	/**
	 * Sets the {@link ClientResources} to reuse the client infrastructure. <br />
	 * Set to {@literal null} to not share resources.
	 *
	 * @param clientResources can be {@literal null}.
	 * @since 1.7
	 * @deprecated since 2.0, configure {@link ClientResources} using {@link LettuceClientConfiguration}.
	 * @throws IllegalStateException if {@link LettuceClientConfiguration} is immutable.
	 */
	@Deprecated
	public void setClientResources(ClientResources clientResources) {
		getMutableConfiguration().setClientResources(clientResources);
	}

	/**
	 * @return the {@link LettuceClientConfiguration}.
	 * @since 2.0
	 */
	public LettuceClientConfiguration getClientConfiguration() {
		return clientConfiguration;
	}

	/**
	 * @return the {@link RedisStandaloneConfiguration}.
	 * @since 2.0
	 */
	public RedisStandaloneConfiguration getStandaloneConfiguration() {
		return standaloneConfig;
	}

	/**
	 * @return the {@link RedisSocketConfiguration} or {@literal null} if not set.
	 * @since 2.1
	 */
	@Nullable
	public RedisSocketConfiguration getSocketConfiguration() {
		return isDomainSocketAware() ? (RedisSocketConfiguration) configuration : null;
	}

	/**
	 * @return the {@link RedisSentinelConfiguration}, may be {@literal null}.
	 * @since 2.0
	 */
	@Nullable
	public RedisSentinelConfiguration getSentinelConfiguration() {
		return isRedisSentinelAware() ? (RedisSentinelConfiguration) configuration : null;
	}

	/**
	 * @return the {@link RedisClusterConfiguration}, may be {@literal null}.
	 * @since 2.0
	 */
	@Nullable
	public RedisClusterConfiguration getClusterConfiguration() {
		return isClusterAware() ? (RedisClusterConfiguration) configuration : null;
	}

	/**
	 * Specifies if pipelined results should be converted to the expected data type. If false, results of
	 * {@link LettuceConnection#closePipeline()} and {LettuceConnection#exec()} will be of the type returned by the
	 * Lettuce driver.
	 *
	 * @return Whether or not to convert pipeline and tx results.
	 */
	public boolean getConvertPipelineAndTxResults() {
		return convertPipelineAndTxResults;
	}

	/**
	 * Specifies if pipelined and transaction results should be converted to the expected data type. If false, results of
	 * {@link LettuceConnection#closePipeline()} and {LettuceConnection#exec()} will be of the type returned by the
	 * Lettuce driver.
	 *
	 * @param convertPipelineAndTxResults Whether or not to convert pipeline and tx results.
	 */
	public void setConvertPipelineAndTxResults(boolean convertPipelineAndTxResults) {
		this.convertPipelineAndTxResults = convertPipelineAndTxResults;
	}

	/**
	 * @return true when {@link RedisStaticMasterReplicaConfiguration} is present.
	 * @since 2.1
	 */
	private boolean isStaticMasterReplicaAware() {
		return RedisConfiguration.isStaticMasterReplicaConfiguration(configuration);
	}

	/**
	 * @return true when {@link RedisSentinelConfiguration} is present.
	 * @since 1.5
	 */
	public boolean isRedisSentinelAware() {
		return RedisConfiguration.isSentinelConfiguration(configuration);
	}

	/**
	 * @return true when {@link RedisSocketConfiguration} is present.
	 * @since 2.1
	 */
	private boolean isDomainSocketAware() {
		return RedisConfiguration.isDomainSocketConfiguration(configuration);
	}

	/**
	 * @return true when {@link RedisClusterConfiguration} is present.
	 * @since 1.7
	 */
	public boolean isClusterAware() {
		return RedisConfiguration.isClusterConfiguration(configuration);
	}

	/**
	 * @return the shared connection using {@literal byte[]} encoding for imperative API use. {@literal null} if
	 *         {@link #getShareNativeConnection() connection sharing} is disabled or when connected to Redis Cluster.
	 */
	@Nullable
	protected StatefulRedisConnection<byte[], byte[]> getSharedConnection() {
		/* ===== L3-05 讲解注释 BY 军火库 =====
		 * <h3>🔥 对照方法 · asyncSharedConn 的来源</h3>
		 *
		 * <p><b>调用方</b>：{@code getConnection()} 创建新的 {@code LettuceConnection}
		 * wrapper 时调用，并把返回值作为构造参数传入。</p>
		 *
		 * <p><b>触发条件</b>：每次业务从 Factory 获取非 Cluster 的命令连接。</p>
		 *
		 * <p><b>做出的决定</b>：当 {@code shareNativeConnection=true} 且不是 Cluster 时，
		 * 返回 JVM 内复用的 {@code SharedConnection}；否则返回 {@code null}。返回 {@code null}
		 * 后，{@code LettuceConnection#getAsyncConnection()} 的普通命令也会退到 dedicated 路径。</p>
		 *
		 * <p><b>跳过它会怎样</b>：看不懂 {@code asyncSharedConn} 为什么有时为 null，
		 * 就会把 {@code shareNativeConnection=false} 误判成“代码用了事务/阻塞命令”。
		 * ===== END ===== */
		return shareNativeConnection && !isClusterAware()
				? (StatefulRedisConnection) getOrCreateSharedConnection().getConnection()
				: null;
	}

	/**
	 * @return the shared cluster connection using {@literal byte[]} encoding for imperative API use. {@literal null} if
	 *         {@link #getShareNativeConnection() connection sharing} is disabled or when connected to Redis
	 *         Standalone/Sentinel/Master-Replica.
	 * @since 2.5.7
	 */
	@Nullable
	protected StatefulRedisClusterConnection<byte[], byte[]> getSharedClusterConnection() {
		return shareNativeConnection && isClusterAware()
				? (StatefulRedisClusterConnection) getOrCreateSharedConnection().getConnection()
				: null;
	}

	/**
	 * @return the shared connection using {@link ByteBuffer} encoding for reactive API use. {@literal null} if
	 *         {@link #getShareNativeConnection() connection sharing} is disabled.
	 * @since 2.0.1
	 */
	@Nullable
	protected StatefulConnection<ByteBuffer, ByteBuffer> getSharedReactiveConnection() {
		return shareNativeConnection ? getOrCreateSharedReactiveConnection().getConnection() : null;
	}

	/**
	 * 创建 Lettuce 连接提供者，确立“连接管理策略”。
	 * <h3>1. 背景概念：汽车租赁公司</h3>
	 * <p>如果 <code>createClient()</code> 是用来制造物理引擎的，那么本方法就是用来开“汽车租赁公司”的。
	 * 它决定了业务代码发命令时，是“买新车”（新建连接）、“合资公交车”（共享单连接），还是“车库借车”（使用连接池）。</p>
	 * <h3>2. 设计模式</h3>
	 * <p>本方法大量运用了<b>装饰器模式（Decorator Pattern）</b>，通过层层包装来增强基础连接的能力。</p>
	 * <h3>🔥 架构师灵魂拷问与真相：为什么需要连接池？</h3>
	 * <blockquote>
	 * <b>矛盾点：</b>Lettuce 天生异步非阻塞，默认共享单一物理连接（<code>shareNativeConnection=true</code>）性能就极高，那为何还要大费周章搞个“连接池”？
	 * <br><br>
	 * <b>架构级真相：</b>对于普通 GET/SET，确实是在复用单一共享连接，无需连接池。<b>但 Redis 有两类特殊场景绝对不能共享连接：</b>
	 * <ol>
	 * <li><b>阻塞式命令（如 BLPOP/BRPOP）：</b>共享连接执行阻塞命令会导致 TCP 通道卡死，其他所有线程的普通命令全部被堵死。</li>
	 * <li><b>事务操作（Transactions）：</b>多线程共享同一连接执行 <code>MULTI</code>，会导致命令严重串线和数据错乱。</li>
	 * </ol>
	 * <br>
	 * <b>核心结论：</b>Spring 遇到上述两类操作时，会向本方法创建的 Provider 申请<b>专属独立连接</b>。
	 * <ol>
	 * <li><b>若未配连接池（Provider）：</b>每次遇到事务或阻塞命令，它就会去临时建立一次代价高昂的 TCP 三次握手，用完立刻关闭（四次挥手），性能极差。</li>
	 * <li><b>若配连接池（Pooling Provider）：</b>则从池子里借一个连接，用完归还</li>
	 * </ol>
	 *
	 * <b>如果你业务中没用到事务或阻塞队列，你配的 Lettuce 连接池（spring.redis.lettuce.pool.max-active 等） 99% 的时间纯粹是摆设！</b>
	 * </blockquote>
	 *
	 * @param client 底层物理客户端引擎
	 * @param codec  用于将 Java 对象与 Redis 字节数组互相转换的序列化解码器
	 * @return 组装完毕的连接提供者
	 */
	private LettuceConnectionProvider createConnectionProvider(AbstractRedisClient client, RedisCodec<?, ?> codec) {
		// 【第一层：历史包袱兼容 (Deprecated)】
		// 兼容 Spring Data Redis 2.0 以前直接注入 LettucePool 的老写法，防止老项目升级报错。
		if (this.pool != null) {
			return new LettucePoolConnectionProvider(this.pool);
		}
		// 【第二层：创建纯粹的基础连接提供者】
		// 将客户端引擎与序列化解码器绑定。这是一个不带任何缓存/池化功能的提供者，每次你找它要连接，它都会去底层建一条全新的 TCP 通道。
		LettuceConnectionProvider connectionProvider = doCreateConnectionProvider(client, codec);

		// 【第三层：现代化的连接池包装 (装饰器模式)】
		// 如果开启了基于 Apache Commons Pool 2 的现代化连接池配置 （Spring Boot 2.0 开始，官方推荐通过 LettucePoolingClientConfiguration 来统一管理高级配置）
		if (this.clientConfiguration instanceof LettucePoolingClientConfiguration) {
			// 给基础类（LettuceConnectionProvider）穿上“池化铠甲”：外界调用 getConnection() 时，池化提供者先看连接池有无空闲连接，有直接拿出来复用；无空闲且未达最大连接数，再调用内部基础 LettuceConnectionProvider 去底层真实地建一条 TCP 连接。
			return new LettucePoolingConnectionProvider(connectionProvider,
					(LettucePoolingClientConfiguration) this.clientConfiguration);
		}

		// 【第四层：无连接池分支】
		// 如果未命中连接池配置，直接返回基础 Provider（遇到需独立连接的场景时，每次都会执行昂贵的 TCP 三次握手）。
		return connectionProvider;
	}

	/**
	 * <h3>1. 核心定位：底层拓扑路由策略器</h3>
	 * <p><code>createConnectionProvider</code> 解决的是<b>“要不要用连接池”</b>的问题（装饰器）。<br>
	 * 而本方法 <code>doCreateConnectionProvider</code> 则是剥去连接池外衣后，底层最核心的<b>“拓扑路由策略器”</b>。
	 * 它的根本任务是决定：当业务发来 GET/SET 命令时，底层究竟把网络包发给哪个 IP 节点？这里会根据单机、主从、集群等架构匹配专属的连接管理类。</p>
	 *
	 * <h3>2. 三大核心架构分支与原理</h3>
	 * <ul>
	 * <li><b>静态主从架构（Static Master-Replica）：</b>死磕 IP 的一主多从。底层会建立特殊连接，遇到 SET 发给主节点，遇到 GET 根据 <code>ReadFrom</code> 策略发给从节点。</li>
	 * <li><b>Redis 集群架构（Cluster）：</b>核心能力是<b>“槽位路由与重定向处理”</b>。遇到 MOVED 或 ASK 错误时，底层会自动重新解析拓扑并发给正确机器，对上层透明。同样的，它也支持集群模式下的从库读写分离（结合 readFrom）</li>
	 * <li><b>单机与哨兵（Standalone / Sentinel）：</b>
	 * <b>💡 架构师避坑点（易混淆）：为何哨兵混在单机里？</b><br>
	 * 真相：在 Lettuce 设计中，哨兵只是一个<b>“寻址服务”</b>。客户端启动时问哨兵拿到 Master IP，随后就用这个 IP 建一条普通单机连接。后续所有读写都顺着这条单机通道发。只有主从切换断连时，才会重新问哨兵。因此它们在行为上完全一致，共用 <code>StandaloneConnectionProvider</code>。</li>
	 * </ul>
	 *
	 * <h3>🔥 实战性能优化建议</h3>
	 * <ul>
	 * <li><b>1. 榨干从库的剩余价值（强烈建议配置 ReadFrom）：</b>重金部署的主从或集群，如果不配置 ReadFrom，所有读写全部压在主库，从库沦为冷板凳。务必在注入配置时显式指定（如 <code>builder.readFrom(ReadFrom.REPLICA_PREFERRED)</code>），瞬间让读吞吐量翻倍！</li>
	 * <li><b>2. 不要在集群模式下瞎配连接池：</b>池化包装在最外层。集群模式本身底层拓扑庞大（动辄几十个节点），若最外层再套庞大的 Pool，会导致连接数呈乘法级爆炸（如 MaxActive(100) * Nodes(6)），极易耗尽物理机句柄。坚持使用 Lettuce 原生的单连接复用才是顶级玩法。</li>
	 * </ul>
	 * <hr/>
	 * Create a {@link LettuceConnectionProvider} given {@link AbstractRedisClient} and {@link RedisCodec}. Configuration
	 * of this connection factory specifies the type of the created connection provider. This method creates either a
	 * {@link LettuceConnectionProvider} for either {@link RedisClient} or {@link RedisClusterClient}. Subclasses may
	 * override this method to decorate the connection provider.
	 *
	 * @param client either {@link RedisClient} or {@link RedisClusterClient}, must not be {@literal null}.
	 * @param codec used for connection creation, must not be {@literal null}. By default, a {@code byte[]} codec.
	 *          Reactive connections require a {@link java.nio.ByteBuffer} codec.
	 * @return the connection provider.
	 * @since 2.1
	 */
	protected LettuceConnectionProvider doCreateConnectionProvider(AbstractRedisClient client, RedisCodec<?, ?> codec) {
		// 【核心预处理：提取读写分离策略】
		// 架构意义：提前抽取你配置的 ReadFrom 策略（如 MASTER 只读主、REPLICA 只读从、NEAREST 读延迟最低的节点等），准备塞给后续的 Provider 以实现读写分离。
		ReadFrom readFrom = getClientConfiguration().getReadFrom().orElse(null);

		// 【分支 1：静态主从架构 (Static Master-Replica)】
		if (isStaticMasterReplicaAware()) {
			// 将配置的主从节点 IP/Port 转为标准的 RedisURI 对象，并强制绑定当前的 Database 索引
			List<RedisURI> nodes = ((RedisStaticMasterReplicaConfiguration) configuration).getNodes().stream() //
					.map(it -> createRedisURIAndApplySettings(it.getHostName(), it.getPort())) //
					.peek(it -> it.setDatabase(getDatabase())) //
					.collect(Collectors.toList());
			// 内部智能路由：SET 走主库，GET 根据 readFrom 策略走从库
			return new StaticMasterReplicaConnectionProvider((RedisClient) client, codec, nodes, readFrom);
		}

		// 【分支 2：Redis 集群架构 (Cluster)】
		if (isClusterAware()) {
			// 核心能力：处理 16384 个哈希槽路由，以及处理 MOVED/ASK 自动重定向机制
			return new ClusterConnectionProvider((RedisClusterClient) client, codec, readFrom);
		}

		// 【分支 3：兜底方案 —— 单机与哨兵 (Standalone / Sentinel)】
		// 哨兵仅做前置寻址，最终真正执行命令的网络通道与普通单机模式无异，顺着一根管子发即可
		return new StandaloneConnectionProvider((RedisClient) client, codec, readFrom);
	}

	/**
	 * 创建底层真实通信客户端的 “策略路由 + 工厂”。
	 * <h3>1. 架构设计视角</h3>
	 * <p>本方法的核心任务是“看碟下菜”：检查注入的 Spring Redis 配置，
	 * 路由并 new 出对应底层真实的 Lettuce 客户端引擎（{@code RedisClient} 或 {@code RedisClusterClient}）。
	 * 同时负责将高级调优参数（{@code ClientResources} 和 {@code ClientOptions}）挂载到客户端中。</p>
	 * <h3>2. 路由支持的四种架构</h3>
	 * <ul>
	 * <li><b>静态主从（Static Master-Replica）：</b>写死 IP 的一主多从读写分离，无 Sentinel 自动选举。</li>
	 * <li><b>哨兵模式（Sentinel）：</b>经典高可用。内部路由由 Sentinel URI 驱动，自动感知主库。</li>
	 * <li><b>集群模式（Cluster）：</b>海量数据哈希槽分片。这是唯一使用重量级 {@code RedisClusterClient} 的场景，需自行维护 Slot 拓扑。</li>
	 * <li><b>单机/Socket（Standalone）：</b>基础单机部署，或追求极致本地 IPC 延迟的 UNIX Domain Socket 通信。</li>
	 * </ul>
	 * * <h3>💡 架构师部署建议与避坑指南：ClientResources 的妙用</h3>
	 * <p>本方法在各个分支中都在调用 <code>clientConfiguration.getClientResources()</code>。
	 * 普通开发通常为 null（走默认创建），但在大厂高并发架构下，这是性能调优的核心命门。</p>
	 * <ul>
	 * <li><b>它里面装了什么？</b> Netty 的 EventLoopGroup（核心 IO 线程池）、DNS 解析器、Metrics 监控收集器等。</li>
	 * <li><b>潜在巨坑：</b> 假设微服务配了 3 个 {@code LettuceConnectionFactory}（分别连订单、用户、风控 Redis）。如果不自定义此资源，Lettuce 会默认建 3 套 Netty 线程池，导致几十个空闲 IO 线程极其浪费 CPU 调度和内存！</li>
	 * <li><b>架构师正确做法：</b> 在 Spring Boot 中手动 <code>@Bean</code> 创建一个全局唯一的 {@code ClientResources}（如限制为 4 个线程），注入给所有 Factory 共享。让干活的 IO 线程永远只有那高效的几个，把资源复用到极致！</li>
	 * </ul>
	 *
	 *  @return 初始化的 Lettuce 原生客户端
	 */
	protected AbstractRedisClient createClient() {

		// 【分支 1：静态主从模式 (Static Master-Replica)】
		// 架构场景：公司搭建主从 Redis（比如一主两从），无哨兵选举，写死 IP 的读写分离。
		if (isStaticMasterReplicaAware()) {
			// 1. 获取客户端资源（ClientResources，包含 Netty 的线程池配置等），利用 Java 8 Optional 优雅处理 ClientResources：有自定义则用，无则无参默认创建
			RedisClient redisClient = clientConfiguration.getClientResources() //
					.map(RedisClient::create) //
					.orElseGet(RedisClient::create);
			// 2. 挂载高级选项（超时时间、SSL等），塞给客户端
			clientConfiguration.getClientOptions().ifPresent(redisClient::setOptions);

			return redisClient;
		}

		// 【分支 2：哨兵模式 (Redis Sentinel - 高可用)】
		// 架构场景：客户端无需知道主库 IP，只需连上哨兵，哨兵会告诉它当前主库在哪。此时创建的虽然也是基础的 RedisClient，但它的内部路由逻辑完全是由 redisURI 中的 Sentinel 信息驱动的。
		if (isRedisSentinelAware()) {
			// 1. 解析出包含哨兵节点列表的 URI 配置（比如 redis-sentinel://127.0.0.1:26379,127.0.0.2:26379?mymaster）
			RedisURI redisURI = getSentinelRedisURI();
			// 2. 结合 URI 和 ClientResources 创建客户端
			RedisClient redisClient = clientConfiguration.getClientResources() //
					.map(clientResources -> RedisClient.create(clientResources, redisURI)) //
					.orElseGet(() -> RedisClient.create(redisURI));

			clientConfiguration.getClientOptions().ifPresent(redisClient::setOptions);
			return redisClient;
		}

		// 【分支 3：集群模式 (Redis Cluster - 分片与海量数据)】
		// 架构场景：单机扛不住的哈希槽 Slot 切分架构。必须使用 RedisClusterClient 因在集群下，客户端需要自己维护“哪个 Key 在哪个节点的哪个槽位上”的路由表（Topology），这个客户端比单机的要重得多。
		if (isClusterAware()) {
			// 1. 把配置文件里的集群所有种子节点（Cluster Nodes）转换成 Lettuce 认识的 RedisURI 列表
			List<RedisURI> initialUris = new ArrayList<>();
			ClusterConfiguration configuration = (ClusterConfiguration) this.configuration;
			for (RedisNode node : configuration.getClusterNodes()) {
				initialUris.add(createRedisURIAndApplySettings(node.getHost(), node.getPort()));
			}
			// 2. 【核心差异】创建针对集群设计的重量级 RedisClusterClient（需自行维护节点与 Slot 拓扑路由表）
			RedisClusterClient clusterClient = clientConfiguration.getClientResources() //
					.map(clientResources -> RedisClusterClient.create(clientResources, initialUris)) //
					.orElseGet(() -> RedisClusterClient.create(initialUris));
			// 3. 设置集群专属的高级选项（比如拓扑自动刷新时间等）
			clusterClient.setOptions(getClusterClientOptions(configuration));

			return clusterClient;
		}


		// 【分支 4：兜底方案 —— 单机模式 (Standalone) 或 UNIX Socket】
		// 架构场景：开发环境传统单机 IP+PORT，或同机部署追求极低延迟的进程间 Socket 通信。
		RedisURI uri = isDomainSocketAware()
				? createRedisSocketURIAndApplySettings(((DomainSocketConfiguration) configuration).getSocket())
				: createRedisURIAndApplySettings(getHostName(), getPort());

		RedisClient redisClient = clientConfiguration.getClientResources() //
				.map(clientResources -> RedisClient.create(clientResources, uri)) //
				.orElseGet(() -> RedisClient.create(uri));
		clientConfiguration.getClientOptions().ifPresent(redisClient::setOptions);

		return redisClient;
	}

	private ClusterClientOptions getClusterClientOptions(ClusterConfiguration configuration) {

		Optional<ClientOptions> clientOptions = clientConfiguration.getClientOptions();
		ClusterClientOptions clusterClientOptions = clientOptions //
				.filter(ClusterClientOptions.class::isInstance) //
				.map(ClusterClientOptions.class::cast) //
				.orElseGet(() -> {
					return clientOptions //
							.map(it -> ClusterClientOptions.builder(it).build()) //
							.orElseGet(ClusterClientOptions::create);
				});

		if (configuration.getMaxRedirects() != null) {
			return clusterClientOptions.mutate().maxRedirects(configuration.getMaxRedirects()).build();
		}

		return clusterClientOptions;
	}

	private RedisURI getSentinelRedisURI() {

		RedisURI redisUri = LettuceConverters.sentinelConfigurationToRedisURI(
				(org.springframework.data.redis.connection.RedisSentinelConfiguration) configuration);

		applyToAll(redisUri, it -> {

			clientConfiguration.getClientName().ifPresent(it::setClientName);

			it.setSsl(clientConfiguration.isUseSsl());
			it.setVerifyPeer(clientConfiguration.isVerifyPeer());
			it.setStartTls(clientConfiguration.isStartTls());
			it.setTimeout(clientConfiguration.getCommandTimeout());
		});

		redisUri.setDatabase(getDatabase());

		return redisUri;
	}

	private void assertInitialized() {
		Assert.state(this.initialized, "LettuceConnectionFactory was not initialized through afterPropertiesSet()");
		Assert.state(!this.destroyed, "LettuceConnectionFactory was destroyed and cannot be used anymore");
	}

	private static void applyToAll(RedisURI source, Consumer<RedisURI> action) {

		action.accept(source);
		source.getSentinels().forEach(action);
	}

	private RedisURI createRedisURIAndApplySettings(String host, int port) {

		RedisURI.Builder builder = RedisURI.Builder.redis(host, port);

		applyAuthentication(builder);

		clientConfiguration.getClientName().ifPresent(builder::withClientName);

		builder.withDatabase(getDatabase());
		builder.withSsl(clientConfiguration.isUseSsl());
		builder.withVerifyPeer(clientConfiguration.isVerifyPeer());
		builder.withStartTls(clientConfiguration.isStartTls());
		builder.withTimeout(clientConfiguration.getCommandTimeout());

		return builder.build();
	}

	private RedisURI createRedisSocketURIAndApplySettings(String socketPath) {

		RedisURI.Builder builder = RedisURI.Builder.socket(socketPath);

		applyAuthentication(builder);
		builder.withDatabase(getDatabase());
		builder.withTimeout(clientConfiguration.getCommandTimeout());

		return builder.build();
	}

	private void applyAuthentication(RedisURI.Builder builder) {

		String username = getRedisUsername();
		if (StringUtils.hasText(username)) {
			// See https://github.com/lettuce-io/lettuce-core/issues/1404
			builder.withAuthentication(username, new String(getRedisPassword().toOptional().orElse(new char[0])));
		} else {
			getRedisPassword().toOptional().ifPresent(builder::withPassword);
		}
	}

	@Override
	public RedisSentinelConnection getSentinelConnection() {

		assertInitialized();

		return new LettuceSentinelConnection(connectionProvider);
	}

	private MutableLettuceClientConfiguration getMutableConfiguration() {

		Assert.state(clientConfiguration instanceof MutableLettuceClientConfiguration,
				() -> String.format("Client configuration must be instance of MutableLettuceClientConfiguration but is %s",
						ClassUtils.getShortName(clientConfiguration.getClass())));

		return (MutableLettuceClientConfiguration) clientConfiguration;
	}

	private long getClientTimeout() {
		return clientConfiguration.getCommandTimeout().toMillis();
	}

	/**
	 * Wrapper for shared connections. Keeps track of the connection lifecycleThe wrapper is thread-safe as it
	 * synchronizes concurrent calls by blocking.
	 *
	 * @param <E> connection encoding.
	 * @author Mark Paluch
	 * @author Christoph Strobl
	 * @since 2.1
	 */
	class SharedConnection<E> {

		private final LettuceConnectionProvider connectionProvider;

		/** Synchronization monitor for the shared Connection */
		private final Object connectionMonitor = new Object();

		private @Nullable StatefulConnection<E, E> connection;

		SharedConnection(LettuceConnectionProvider connectionProvider) {
			this.connectionProvider = connectionProvider;
		}

		/**
		 * Returns a valid Lettuce connection. Initializes and validates the connection if
		 * {@link #setValidateConnection(boolean) enabled}.
		 *
		 * @return the connection.
		 */
		@Nullable
		StatefulConnection<E, E> getConnection() {

			synchronized (this.connectionMonitor) {

				if (this.connection == null) {
					this.connection = getNativeConnection();
				}

				if (getValidateConnection()) {
					validateConnection();
				}

				return this.connection;
			}
		}

		/**
		 * Obtain a connection from the associated {@link LettuceConnectionProvider}.
		 *
		 * @return the connection.
		 */
		private StatefulConnection<E, E> getNativeConnection() {
			return connectionProvider.getConnection(StatefulConnection.class);
		}

		/**
		 * Validate the connection. Invalid connections will be closed and the connection state will be reset.
		 */
		void validateConnection() {

			synchronized (this.connectionMonitor) {

				boolean valid = false;

				if (connection != null && connection.isOpen()) {
					try {

						if (connection instanceof StatefulRedisConnection) {
							((StatefulRedisConnection) connection).sync().ping();
						}

						if (connection instanceof StatefulRedisClusterConnection) {
							((StatefulRedisClusterConnection) connection).sync().ping();
						}
						valid = true;
					} catch (Exception e) {
						log.debug("Validation failed", e);
					}
				}

				if (!valid) {

					log.info("Validation of shared connection failed. Creating a new connection.");
					resetConnection();
					this.connection = getNativeConnection();
				}
			}
		}

		/**
		 * Reset the underlying shared Connection, to be reinitialized on next access.
		 */
		void resetConnection() {

			synchronized (this.connectionMonitor) {

				if (this.connection != null) {
					this.connectionProvider.release(this.connection);
				}

				this.connection = null;
			}
		}
	}

	/**
	 * Mutable implementation of {@link LettuceClientConfiguration}.
	 *
	 * @author Mark Paluch
	 * @author Christoph Strobl
	 */
	static class MutableLettuceClientConfiguration implements LettuceClientConfiguration {

		private boolean useSsl;
		private boolean verifyPeer = true;
		private boolean startTls;
		private @Nullable ClientResources clientResources;
		private @Nullable String clientName;
		private Duration timeout = Duration.ofSeconds(RedisURI.DEFAULT_TIMEOUT);
		private Duration shutdownTimeout = Duration.ofMillis(100);

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration#isUseSsl()
		 */
		@Override
		public boolean isUseSsl() {
			return useSsl;
		}

		void setUseSsl(boolean useSsl) {
			this.useSsl = useSsl;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration#isVerifyPeer()
		 */
		@Override
		public boolean isVerifyPeer() {
			return verifyPeer;
		}

		void setVerifyPeer(boolean verifyPeer) {
			this.verifyPeer = verifyPeer;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration#isStartTls()
		 */
		@Override
		public boolean isStartTls() {
			return startTls;
		}

		void setStartTls(boolean startTls) {
			this.startTls = startTls;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration#getClientResources()
		 */
		@Override
		public Optional<ClientResources> getClientResources() {
			return Optional.ofNullable(clientResources);
		}

		void setClientResources(ClientResources clientResources) {
			this.clientResources = clientResources;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration#getClientOptions()
		 */
		@Override
		public Optional<ClientOptions> getClientOptions() {
			return Optional.empty();
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration#getReadFrom()
		 */
		@Override
		public Optional<ReadFrom> getReadFrom() {
			return Optional.empty();
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration#getClientName()
		 */
		@Override
		public Optional<String> getClientName() {
			return Optional.ofNullable(clientName);
		}

		/**
		 * @param clientName can be {@literal null}.
		 * @since 2.1
		 */
		void setClientName(@Nullable String clientName) {
			this.clientName = clientName;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration#getTimeout()
		 */
		@Override
		public Duration getCommandTimeout() {
			return timeout;
		}

		void setTimeout(Duration timeout) {
			this.timeout = timeout;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration#getShutdownTimeout()
		 */
		@Override
		public Duration getShutdownTimeout() {
			return shutdownTimeout;
		}

		void setShutdownTimeout(Duration shutdownTimeout) {
			this.shutdownTimeout = shutdownTimeout;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration#getShutdownQuietPeriod()
		 */
		@Override
		public Duration getShutdownQuietPeriod() {
			return shutdownTimeout;
		}
	}

	/**
	 * {@link LettuceConnectionProvider} that translates connection exceptions into {@link RedisConnectionException}.
	 */
	private static class ExceptionTranslatingConnectionProvider
			implements LettuceConnectionProvider, LettuceConnectionProvider.TargetAware, DisposableBean {

		private final LettuceConnectionProvider delegate;

		public ExceptionTranslatingConnectionProvider(LettuceConnectionProvider delegate) {
			this.delegate = delegate;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider#getConnection(java.lang.Class)
		 */
		@Override
		public <T extends StatefulConnection<?, ?>> T getConnection(Class<T> connectionType) {

			try {
				return delegate.getConnection(connectionType);
			} catch (RuntimeException e) {
				throw translateException(e);
			}
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider#getConnection(java.lang.Class, RedisURI)
		 */
		@Override
		public <T extends StatefulConnection<?, ?>> T getConnection(Class<T> connectionType, RedisURI redisURI) {

			try {
				return ((TargetAware) delegate).getConnection(connectionType, redisURI);
			} catch (RuntimeException e) {
				throw translateException(e);
			}
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider#getConnectionAsync(java.lang.Class)
		 */
		@Override
		public <T extends StatefulConnection<?, ?>> CompletionStage<T> getConnectionAsync(Class<T> connectionType) {

			CompletableFuture<T> future = new CompletableFuture<>();

			delegate.getConnectionAsync(connectionType).whenComplete((t, throwable) -> {

				if (throwable != null) {
					future.completeExceptionally(translateException(throwable));
				} else {
					future.complete(t);
				}
			});

			return future;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider#getConnectionAsync(java.lang.Class, RedisURI)
		 */
		@Override
		public <T extends StatefulConnection<?, ?>> CompletionStage<T> getConnectionAsync(Class<T> connectionType,
				RedisURI redisURI) {

			CompletableFuture<T> future = new CompletableFuture<>();

			((TargetAware) delegate).getConnectionAsync(connectionType, redisURI).whenComplete((t, throwable) -> {

				if (throwable != null) {
					future.completeExceptionally(translateException(throwable));
				} else {
					future.complete(t);
				}
			});

			return future;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider#release(io.lettuce.core.api.StatefulConnection)
		 */
		@Override
		public void release(StatefulConnection<?, ?> connection) {
			delegate.release(connection);
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider#releaseAsync(io.lettuce.core.api.StatefulConnection)
		 */
		@Override
		public CompletableFuture<Void> releaseAsync(StatefulConnection<?, ?> connection) {
			return delegate.releaseAsync(connection);
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.beans.factory.DisposableBean#destroy()
		 */
		@Override
		public void destroy() throws Exception {

			if (delegate instanceof DisposableBean) {
				((DisposableBean) delegate).destroy();
			}
		}

		private RuntimeException translateException(Throwable e) {
			return e instanceof RedisConnectionFailureException ? (RedisConnectionFailureException) e
					: new RedisConnectionFailureException("Unable to connect to Redis", e);
		}

	}
}
