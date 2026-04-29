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
package org.springframework.data.redis.connection.jedis;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisSentinelPool;
import redis.clients.jedis.JedisShardInfo;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.util.Pool;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.data.redis.ExceptionTranslationStrategy;
import org.springframework.data.redis.PassThroughExceptionTranslationStrategy;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.RedisConfiguration.SentinelConfiguration;
import org.springframework.data.redis.connection.RedisConfiguration.WithDatabaseIndex;
import org.springframework.data.redis.connection.RedisConfiguration.WithPassword;
import org.springframework.data.redis.connection.jedis.JedisClusterConnection.JedisClusterTopologyProvider;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Connection factory creating <a href="https://github.com/xetorthio/jedis">Jedis</a> based connections.
 * <p>
 * {@link JedisConnectionFactory} should be configured using an environmental configuration and the
 * {@link JedisClientConfiguration client configuration}. Jedis supports the following environmental configurations:
 * <ul>
 * <li>{@link RedisStandaloneConfiguration}</li>
 * <li>{@link RedisSentinelConfiguration}</li>
 * <li>{@link RedisClusterConfiguration}</li>
 * </ul>
 * <p>
 * This connection factory must be {@link #afterPropertiesSet() initialized} prior to {@link #getConnection obtaining
 * connections}.
 *
 * @author Costin Leau
 * @author Thomas Darimont
 * @author Christoph Strobl
 * @author Mark Paluch
 * @author Fu Jian
 * @author Ajith Kumar
 * @see JedisClientConfiguration
 * @see Jedis
 */
public class JedisConnectionFactory implements InitializingBean, DisposableBean, RedisConnectionFactory {

	private final static Log log = LogFactory.getLog(JedisConnectionFactory.class);
	private static final ExceptionTranslationStrategy EXCEPTION_TRANSLATION = new PassThroughExceptionTranslationStrategy(
			JedisConverters.exceptionConverter());

	private final JedisClientConfiguration clientConfiguration;
	private @Nullable JedisShardInfo shardInfo;
	private JedisClientConfig clientConfig = DefaultJedisClientConfig.builder().build();
	private boolean providedShardInfo = false;
	private @Nullable Pool<Jedis> pool;
	private boolean convertPipelineAndTxResults = true;
	private RedisStandaloneConfiguration standaloneConfig = new RedisStandaloneConfiguration("localhost",
			Protocol.DEFAULT_PORT);

	private @Nullable RedisConfiguration configuration;

	private @Nullable JedisCluster cluster;
	private @Nullable ClusterTopologyProvider topologyProvider;
	private @Nullable ClusterCommandExecutor clusterCommandExecutor;

	private boolean initialized;
	private boolean destroyed;

	/**
	 * Constructs a new <code>JedisConnectionFactory</code> instance with default settings (default connection pooling, no
	 * shard information).
	 */
	public JedisConnectionFactory() {
		this(new MutableJedisClientConfiguration());
	}

	/**
	 * Constructs a new {@link JedisConnectionFactory} instance given {@link JedisClientConfiguration}.
	 *
	 * @param clientConfig must not be {@literal null}
	 * @since 2.0
	 */
	private JedisConnectionFactory(JedisClientConfiguration clientConfig) {

		Assert.notNull(clientConfig, "JedisClientConfiguration must not be null!");

		this.clientConfiguration = clientConfig;
	}

	/**
	 * Constructs a new <code>JedisConnectionFactory</code> instance. Will override the other connection parameters passed
	 * to the factory.
	 *
	 * @param shardInfo shard information
	 * @deprecated since 2.0, configure Jedis with {@link JedisClientConfiguration} and
	 *             {@link RedisStandaloneConfiguration}.
	 */
	@Deprecated
	public JedisConnectionFactory(JedisShardInfo shardInfo) {

		this(MutableJedisClientConfiguration.create(shardInfo));

		this.shardInfo = shardInfo;
		this.providedShardInfo = true;
	}

	/**
	 * Constructs a new <code>JedisConnectionFactory</code> instance using the given pool configuration.
	 *
	 * @param poolConfig pool configuration
	 */
	public JedisConnectionFactory(JedisPoolConfig poolConfig) {
		this((RedisSentinelConfiguration) null, poolConfig);
	}

	/**
	 * Constructs a new {@link JedisConnectionFactory} instance using the given {@link JedisPoolConfig} applied to
	 * {@link JedisSentinelPool}.
	 *
	 * @param sentinelConfig must not be {@literal null}.
	 * @since 1.4
	 */
	public JedisConnectionFactory(RedisSentinelConfiguration sentinelConfig) {
		this(sentinelConfig, new MutableJedisClientConfiguration());
	}

	/**
	 * Constructs a new {@link JedisConnectionFactory} instance using the given {@link JedisPoolConfig} applied to
	 * {@link JedisSentinelPool}.
	 *
	 * @param sentinelConfig the sentinel configuration to use.
	 * @param poolConfig pool configuration. Defaulted to new instance if {@literal null}.
	 * @since 1.4
	 */
	public JedisConnectionFactory(RedisSentinelConfiguration sentinelConfig, JedisPoolConfig poolConfig) {

		this.configuration = sentinelConfig;
		this.clientConfiguration = MutableJedisClientConfiguration
				.create(poolConfig != null ? poolConfig : new JedisPoolConfig());
	}

	/**
	 * Constructs a new {@link JedisConnectionFactory} instance using the given {@link RedisClusterConfiguration} applied
	 * to create a {@link JedisCluster}.
	 *
	 * @param clusterConfig must not be {@literal null}.
	 * @since 1.7
	 */
	public JedisConnectionFactory(RedisClusterConfiguration clusterConfig) {
		this(clusterConfig, new MutableJedisClientConfiguration());
	}

	/**
	 * Constructs a new {@link JedisConnectionFactory} instance using the given {@link RedisClusterConfiguration} applied
	 * to create a {@link JedisCluster}.
	 *
	 * @param clusterConfig must not be {@literal null}.
	 * @since 1.7
	 */
	public JedisConnectionFactory(RedisClusterConfiguration clusterConfig, JedisPoolConfig poolConfig) {

		Assert.notNull(clusterConfig, "RedisClusterConfiguration must not be null!");

		this.configuration = clusterConfig;
		this.clientConfiguration = MutableJedisClientConfiguration.create(poolConfig);
	}

	/**
	 * Constructs a new {@link JedisConnectionFactory} instance using the given {@link RedisStandaloneConfiguration}.
	 *
	 * @param standaloneConfig must not be {@literal null}.
	 * @since 2.0
	 */
	public JedisConnectionFactory(RedisStandaloneConfiguration standaloneConfig) {
		this(standaloneConfig, new MutableJedisClientConfiguration());
	}

	/**
	 * Constructs a new {@link JedisConnectionFactory} instance using the given {@link RedisStandaloneConfiguration} and
	 * {@link JedisClientConfiguration}.
	 *
	 * @param standaloneConfig must not be {@literal null}.
	 * @param clientConfig must not be {@literal null}.
	 * @since 2.0
	 */
	public JedisConnectionFactory(RedisStandaloneConfiguration standaloneConfig, JedisClientConfiguration clientConfig) {

		this(clientConfig);

		Assert.notNull(standaloneConfig, "RedisStandaloneConfiguration must not be null!");

		this.standaloneConfig = standaloneConfig;
	}

	/**
	 * Constructs a new {@link JedisConnectionFactory} instance using the given {@link RedisSentinelConfiguration} and
	 * {@link JedisClientConfiguration}.
	 *
	 * @param sentinelConfig must not be {@literal null}.
	 * @param clientConfig must not be {@literal null}.
	 * @since 2.0
	 */
	public JedisConnectionFactory(RedisSentinelConfiguration sentinelConfig, JedisClientConfiguration clientConfig) {

		this(clientConfig);

		Assert.notNull(sentinelConfig, "RedisSentinelConfiguration must not be null!");

		this.configuration = sentinelConfig;
	}

	/**
	 * Constructs a new {@link JedisConnectionFactory} instance using the given {@link RedisClusterConfiguration} and
	 * {@link JedisClientConfiguration}.
	 *
	 * @param clusterConfig must not be {@literal null}.
	 * @param clientConfig must not be {@literal null}.
	 * @since 2.0
	 */
	public JedisConnectionFactory(RedisClusterConfiguration clusterConfig, JedisClientConfiguration clientConfig) {

		this(clientConfig);

		Assert.notNull(clusterConfig, "RedisClusterConfiguration must not be null!");

		this.configuration = clusterConfig;
	}

	/**
	 * Returns a Jedis instance to be used as a Redis connection. The instance can be newly created or retrieved from a
	 * pool.
	 *
	 * @return Jedis instance ready for wrapping into a {@link RedisConnection}.
	 */
	protected Jedis fetchJedisConnector() {
		try {
			// 【痛点所在】：如果你配置了连接池（通常都会配）
			if (getUsePool() && pool != null) {
				// 这行代码是去 Apache Commons Pool 2 物理连接池里，
				// “抢/借”一个真实物理 TCP 连接（如果池子空了，当前线程就会阻塞在这里等）
				return pool.getResource();
			}
			// 如果你头铁没配连接池，每次执行到这里
			Jedis jedis = createJedis();
			// 就会发起一次真实的 TCP 三次握手建连！
			// force initialization (see Jedis issue #82)
			jedis.connect();

			return jedis;
		} catch (Exception ex) {
			throw new RedisConnectionFailureException("Cannot get Jedis connection", ex);
		}
	}

	private Jedis createJedis() {

		if (providedShardInfo) {
			return new Jedis(getShardInfo());
		}

		return new Jedis(new HostAndPort(getHostName(), getPort()), this.clientConfig);
	}

	/**
	 * Post process a newly retrieved connection. Useful for decorating or executing initialization commands on a new
	 * connection. This implementation simply returns the connection.
	 *
	 * @param connection the jedis connection.
	 * @return processed connection
	 */
	protected JedisConnection postProcessConnection(JedisConnection connection) {
		return connection;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
	 */
	public void afterPropertiesSet() {

		clientConfig = createClientConfig(getDatabase(), getRedisUsername(), getRedisPassword());

		if (shardInfo == null && clientConfiguration instanceof MutableJedisClientConfiguration) {

			providedShardInfo = false;
			shardInfo = new JedisShardInfo(getHostName(), getPort(), isUseSsl(), //
					clientConfiguration.getSslSocketFactory().orElse(null), //
					clientConfiguration.getSslParameters().orElse(null), //
					clientConfiguration.getHostnameVerifier().orElse(null));

			getRedisPassword().map(String::new).ifPresent(shardInfo::setPassword);
			String username = getRedisUsername();
			if (StringUtils.hasText(username)) {
				shardInfo.setUser(username);
			}

			int readTimeout = getReadTimeout();

			if (readTimeout > 0) {
				shardInfo.setSoTimeout(readTimeout);
			}

			getMutableConfiguration().setShardInfo(shardInfo);
		}

		if (getUsePool() && !isRedisClusterAware()) {
			this.pool = createPool();
		}

		if (isRedisClusterAware()) {

			this.cluster = createCluster();
			this.topologyProvider = createTopologyProvider(this.cluster);
			this.clusterCommandExecutor = new ClusterCommandExecutor(this.topologyProvider,
					new JedisClusterConnection.JedisClusterNodeResourceProvider(this.cluster, this.topologyProvider),
					EXCEPTION_TRANSLATION);
		}

		this.initialized = true;
	}

	JedisClientConfig createSentinelClientConfig(SentinelConfiguration sentinelConfiguration) {
		return createClientConfig(0, sentinelConfiguration.getSentinelUsername(),
				sentinelConfiguration.getSentinelPassword());
	}

	private JedisClientConfig createClientConfig(int database, @Nullable String username, RedisPassword password) {

		DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder();

		clientConfiguration.getClientName().ifPresent(builder::clientName);
		builder.connectionTimeoutMillis(getConnectTimeout());
		builder.socketTimeoutMillis(getReadTimeout());

		builder.database(database);

		if (!ObjectUtils.isEmpty(username)) {
			builder.user(username);
		}
		password.toOptional().map(String::new).ifPresent(builder::password);

		if (isUseSsl()) {

			builder.ssl(true);

			clientConfiguration.getSslSocketFactory().ifPresent(builder::sslSocketFactory);
			clientConfiguration.getHostnameVerifier().ifPresent(builder::hostnameVerifier);
			clientConfiguration.getSslParameters().ifPresent(builder::sslParameters);
		}

		return builder.build();
	}

	private Pool<Jedis> createPool() {

		if (isRedisSentinelAware()) {
			return createRedisSentinelPool((RedisSentinelConfiguration) this.configuration);
		}
		return createRedisPool();
	}

	/**
	 * Creates {@link JedisSentinelPool}.
	 *
	 * @param config the actual {@link RedisSentinelConfiguration}. Never {@literal null}.
	 * @return the {@link Pool} to use. Never {@literal null}.
	 * @since 1.4
	 */
	protected Pool<Jedis> createRedisSentinelPool(RedisSentinelConfiguration config) {

		GenericObjectPoolConfig<Jedis> poolConfig = getPoolConfig() != null ? getPoolConfig() : new JedisPoolConfig();

		JedisClientConfig sentinelConfig = createSentinelClientConfig(config);
		return new JedisSentinelPool(config.getMaster().getName(), convertToJedisSentinelSet(config.getSentinels()),
				poolConfig, this.clientConfig, sentinelConfig);
	}

	/**
	 * Creates {@link JedisPool}.
	 *
	 * @return the {@link Pool} to use. Never {@literal null}.
	 * @since 1.4
	 */
	protected Pool<Jedis> createRedisPool() {
		return new JedisPool(getPoolConfig(), new HostAndPort(getHostName(), getPort()), this.clientConfig);
	}

	private JedisCluster createCluster() {
		return createCluster((RedisClusterConfiguration) this.configuration, getPoolConfig());
	}

	/**
	 * Template method to create a {@link ClusterTopologyProvider} given {@link JedisCluster}. Creates
	 * {@link JedisClusterTopologyProvider} by default.
	 *
	 * @param cluster the {@link JedisCluster}, must not be {@literal null}.
	 * @return the {@link ClusterTopologyProvider}.
	 * @see JedisClusterTopologyProvider
	 * @see 2.2
	 */
	protected ClusterTopologyProvider createTopologyProvider(JedisCluster cluster) {
		return new JedisClusterTopologyProvider(cluster);
	}

	/**
	 * Creates {@link JedisCluster} for given {@link RedisClusterConfiguration} and {@link GenericObjectPoolConfig}.
	 *
	 * @param clusterConfig must not be {@literal null}.
	 * @param poolConfig can be {@literal null}.
	 * @return the actual {@link JedisCluster}.
	 * @since 1.7
	 */
	protected JedisCluster createCluster(RedisClusterConfiguration clusterConfig,
			GenericObjectPoolConfig<Jedis> poolConfig) {

		Assert.notNull(clusterConfig, "Cluster configuration must not be null!");

		Set<HostAndPort> hostAndPort = new HashSet<>();
		for (RedisNode node : clusterConfig.getClusterNodes()) {
			hostAndPort.add(new HostAndPort(node.getHost(), node.getPort()));
		}

		int redirects = clusterConfig.getMaxRedirects() != null ? clusterConfig.getMaxRedirects() : 5;

		return new JedisCluster(hostAndPort, this.clientConfig, redirects, poolConfig);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.beans.factory.DisposableBean#destroy()
	 */
	public void destroy() {

		if (getUsePool() && pool != null) {

			try {
				pool.destroy();
			} catch (Exception ex) {
				log.warn("Cannot properly close Jedis pool", ex);
			}
			pool = null;
		}

		if (cluster != null) {

			try {
				cluster.close();
			} catch (Exception ex) {
				log.warn("Cannot properly close Jedis cluster", ex);
			}

			try {
				clusterCommandExecutor.destroy();
			} catch (Exception ex) {
				log.warn("Cannot properly close cluster command executor", ex);
			}
		}

		this.destroyed = true;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnectionFactory#getConnection()
	 */

	/**
	 * 获取与 Redis 服务器通信的 Jedis 连接对象。
	 * <hr>
	 * <h3>1. 核心定位：“传统车行”的交付提车</h3>
	 * <p>与 <code>LettuceConnectionFactory</code> 中只是套了一层内存包装壳不同，这里是老牌 Jedis 的世界。<br>
	 * 它的核心任务是去底层真实地“借出”或“新建”一个重量级的物理 TCP 连接，并包装成 Spring 标准接口交出去。</p>
	 *
	 * <h3>🔥 架构师视角：通过源码看清 Jedis 与 Lettuce 的底层鸿沟</h3>
	 * <blockquote>
	 * 将 Lettuce 源码与这段 Jedis 源码放在一起对比，架构高下立判：
	 * <ul>
	 * <li><b>1. 物理资源的极度浪费 vs 极致复用：</b>
	 * <br>- <b>Jedis：</b>当有 1000 个并发线程时，Jedis 会在此处的 <code>fetchJedisConnector()</code> 真实地向服务器索要 1000 个 TCP 物理连接！这不仅耗尽连接池，也极大地挤占了服务端的内存和端口资源。
	 * <br>- <b>Lettuce：</b>同样 1000 个并发，Lettuce 仅仅是在 JVM 内存里 new 了 1000 个 Java 包装壳，底层大家都在通过 Netty 往<b>同一个物理 TCP 通道</b>里塞数据报文。</li>
	 * * <li><b>2. 致命的线程阻塞隐患：</b>
	 * <br>- <b>Jedis：</b>若连接池 <code>max-active = 50</code>，第 51 个并发请求到来时，会在此处被彻底阻塞卡死，只能苦等别人归还连接。
	 * <br>- <b>Lettuce：</b>大家都在同一个物理通道发非阻塞命令，根本不存在“借空连接池”的概念（特殊事务/阻塞命令除外），并发 10000 也不会因连接不足而阻塞线程。</li>
	 * </ul>
	 * <b>总结：</b>正因为 Jedis 底层采用<b>“重量级独占物理连接”</b>模式，性能存在天花板。所以从 Spring Boot 2.0 开始，官方毫不犹豫地将默认驱动从 Jedis 换成了基于 Netty 的 Lettuce！
	 * </blockquote>
	 *
	 * @return 包装适配好的 Spring 标准 RedisConnection
	 */
	public RedisConnection getConnection() {
		// 【步骤 1：营业前的安全检查】
		// 确保当前 Factory 已经完成了 afterPropertiesSet() 的初始化动作
		assertInitialized();
		// 【步骤 2：集群架构的特殊通道】
		// 集群版与单机版的底层逻辑不同，必须分流处理
		if (isRedisClusterAware()) {
			return getClusterConnection();
		}

		// 【步骤 3：绝对核心差异 —— 获取真实物理连接】
		// fetchJedisConnector() 会老老实实去连接池 (pool.getResource()) 拿一个，或者新建一个真实的 TCP 物理连接
		Jedis jedis = fetchJedisConnector();
		JedisClientConfig sentinelConfig = this.clientConfig;

		// 【步骤 4：解析哨兵配置】
		SentinelConfiguration sentinelConfiguration = getSentinelConfiguration();
		if (sentinelConfiguration != null) {
			sentinelConfig = createSentinelClientConfig(sentinelConfiguration);
		}

		// 【步骤 5：组装 Spring 标准包装类】
		// 重点：传给 JedisConnection 的第一个参数，是一个被当前线程【独占】的 jedis 物理实例！
		JedisConnection connection = (getUsePool() ? new JedisConnection(jedis, pool, this.clientConfig, sentinelConfig)
				: new JedisConnection(jedis, null, this.clientConfig, sentinelConfig));
		// 统一流水线标准，开启结果转换开关
		connection.setConvertPipelineAndTxResults(convertPipelineAndTxResults);
		return postProcessConnection(connection);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnectionFactory#getClusterConnection()
	 */
	@Override
	public RedisClusterConnection getClusterConnection() {

		assertInitialized();

		if (!isRedisClusterAware()) {
			throw new InvalidDataAccessApiUsageException("Cluster is not configured!");
		}
		return new JedisClusterConnection(this.cluster, this.clusterCommandExecutor, this.topologyProvider);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.dao.support.PersistenceExceptionTranslator#translateExceptionIfPossible(java.lang.RuntimeException)
	 */
	public DataAccessException translateExceptionIfPossible(RuntimeException ex) {
		return EXCEPTION_TRANSLATION.translate(ex);
	}

	/**
	 * Returns the Redis hostname.
	 *
	 * @return the hostName.
	 */
	public String getHostName() {
		return standaloneConfig.getHostName();
	}

	/**
	 * Sets the Redis hostname.
	 *
	 * @param hostName the hostname to set.
	 * @deprecated since 2.0, configure the hostname using {@link RedisStandaloneConfiguration}.
	 */
	@Deprecated
	public void setHostName(String hostName) {
		standaloneConfig.setHostName(hostName);
	}

	/**
	 * Returns whether to use SSL.
	 *
	 * @return use of SSL.
	 * @since 1.8
	 */
	public boolean isUseSsl() {
		return clientConfiguration.isUseSsl();
	}

	/**
	 * Sets whether to use SSL.
	 *
	 * @param useSsl {@literal true} to use SSL.
	 * @since 1.8
	 * @deprecated since 2.0, configure the SSL usage with {@link JedisClientConfiguration}.
	 * @throws IllegalStateException if {@link JedisClientConfiguration} is immutable.
	 */
	@Deprecated
	public void setUseSsl(boolean useSsl) {
		getMutableConfiguration().setUseSsl(useSsl);
	}

	/**
	 * Returns the password used for authenticating with the Redis server.
	 *
	 * @return password for authentication.
	 */
	@Nullable
	public String getPassword() {
		return getRedisPassword().map(String::new).orElse(null);
	}

	@Nullable
	private String getRedisUsername() {
		return RedisConfiguration.getUsernameOrElse(this.configuration, standaloneConfig::getUsername);
	}

	private RedisPassword getRedisPassword() {
		return RedisConfiguration.getPasswordOrElse(this.configuration, standaloneConfig::getPassword);
	}

	/**
	 * Sets the password used for authenticating with the Redis server.
	 *
	 * @param password the password to set.
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
	 * Returns the port used to connect to the Redis instance.
	 *
	 * @return the Redis port.
	 */
	public int getPort() {
		return standaloneConfig.getPort();
	}

	/**
	 * Sets the port used to connect to the Redis instance.
	 *
	 * @param port the Redis port.
	 * @deprecated since 2.0, configure the port using {@link RedisStandaloneConfiguration}.
	 */
	@Deprecated
	public void setPort(int port) {
		standaloneConfig.setPort(port);
	}

	/**
	 * Returns the shardInfo.
	 *
	 * @return the shardInfo.
	 * @deprecated since 2.0.
	 */
	@Deprecated
	@Nullable
	public JedisShardInfo getShardInfo() {
		return shardInfo;
	}

	/**
	 * Sets the shard info for this factory.
	 *
	 * @param shardInfo the shardInfo to set.
	 * @deprecated since 2.0, configure the individual properties from {@link JedisShardInfo} using
	 *             {@link JedisClientConfiguration}.
	 * @throws IllegalStateException if {@link JedisClientConfiguration} is immutable.
	 */
	@Deprecated
	public void setShardInfo(JedisShardInfo shardInfo) {

		this.shardInfo = shardInfo;
		this.providedShardInfo = true;
		getMutableConfiguration().setShardInfo(shardInfo);
	}

	/**
	 * Returns the timeout.
	 *
	 * @return the timeout.
	 */
	public int getTimeout() {
		return getReadTimeout();
	}

	/**
	 * Sets the timeout.
	 *
	 * @param timeout the timeout to set.
	 * @deprecated since 2.0, configure the timeout using {@link JedisClientConfiguration}.
	 * @throws IllegalStateException if {@link JedisClientConfiguration} is immutable.
	 */
	@Deprecated
	public void setTimeout(int timeout) {

		getMutableConfiguration().setReadTimeout(Duration.ofMillis(timeout));
		getMutableConfiguration().setConnectTimeout(Duration.ofMillis(timeout));
	}

	/**
	 * Indicates the use of a connection pool.
	 * <p>
	 * Applies only to single node Redis. Sentinel and Cluster modes use always connection-pooling regardless of the
	 * pooling setting.
	 *
	 * @return the use of connection pooling.
	 */
	public boolean getUsePool() {

		// Jedis Sentinel cannot operate without a pool.
		if (isRedisSentinelAware()) {
			return true;
		}

		return clientConfiguration.isUsePooling();
	}

	/**
	 * Turns on or off the use of connection pooling.
	 *
	 * @param usePool the usePool to set.
	 * @deprecated since 2.0, configure pooling usage with {@link JedisClientConfiguration}.
	 * @throws IllegalStateException if {@link JedisClientConfiguration} is immutable.
	 * @throws IllegalStateException if configured to use sentinel and {@code usePool} is {@literal false} as Jedis
	 *           requires pooling for Redis sentinel use.
	 */
	@Deprecated
	public void setUsePool(boolean usePool) {

		if (isRedisSentinelAware() && !usePool) {
			throw new IllegalStateException("Jedis requires pooling for Redis Sentinel use!");
		}

		getMutableConfiguration().setUsePooling(usePool);
	}

	/**
	 * Returns the poolConfig.
	 *
	 * @return the poolConfig
	 */
	@Nullable
	public GenericObjectPoolConfig<Jedis> getPoolConfig() {
		return clientConfiguration.getPoolConfig().orElse(null);
	}

	/**
	 * Sets the pool configuration for this factory.
	 *
	 * @param poolConfig the poolConfig to set.
	 * @deprecated since 2.0, configure {@link JedisPoolConfig} using {@link JedisClientConfiguration}.
	 * @throws IllegalStateException if {@link JedisClientConfiguration} is immutable.
	 */
	@Deprecated
	public void setPoolConfig(JedisPoolConfig poolConfig) {
		getMutableConfiguration().setPoolConfig(poolConfig);
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
	 * @param index database index.
	 * @deprecated since 2.0, configure the client name using {@link RedisSentinelConfiguration} or
	 *             {@link RedisStandaloneConfiguration}.
	 */
	@Deprecated
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
	 * @return the client name.
	 * @since 1.8
	 */
	@Nullable
	public String getClientName() {
		return clientConfiguration.getClientName().orElse(null);
	}

	/**
	 * Sets the client name used by this connection factory. Defaults to none which does not set a client name.
	 *
	 * @param clientName the client name.
	 * @since 1.8
	 * @deprecated since 2.0, configure the client name using {@link JedisClientConfiguration}.
	 * @throws IllegalStateException if {@link JedisClientConfiguration} is immutable.
	 */
	@Deprecated
	public void setClientName(String clientName) {
		this.getMutableConfiguration().setClientName(clientName);
	}

	/**
	 * @return the {@link JedisClientConfiguration}.
	 * @since 2.0
	 */
	public JedisClientConfiguration getClientConfiguration() {
		return clientConfiguration;
	}

	/**
	 * @return the {@link RedisStandaloneConfiguration}.
	 * @since 2.0
	 */
	@Nullable
	public RedisStandaloneConfiguration getStandaloneConfiguration() {
		return standaloneConfig;
	}

	/**
	 * @return the {@link RedisStandaloneConfiguration}, may be {@literal null}.
	 * @since 2.0
	 */
	@Nullable
	public RedisSentinelConfiguration getSentinelConfiguration() {
		return RedisConfiguration.isSentinelConfiguration(configuration) ? (RedisSentinelConfiguration) configuration
				: null;
	}

	/**
	 * @return the {@link RedisClusterConfiguration}, may be {@literal null}.
	 * @since 2.0
	 */
	@Nullable
	public RedisClusterConfiguration getClusterConfiguration() {
		return RedisConfiguration.isClusterConfiguration(configuration) ? (RedisClusterConfiguration) configuration : null;
	}

	/**
	 * Specifies if pipelined results should be converted to the expected data type. If false, results of
	 * {@link JedisConnection#closePipeline()} and {@link JedisConnection#exec()} will be of the type returned by the
	 * Jedis driver.
	 *
	 * @return Whether or not to convert pipeline and tx results.
	 */
	public boolean getConvertPipelineAndTxResults() {
		return convertPipelineAndTxResults;
	}

	/**
	 * Specifies if pipelined results should be converted to the expected data type. If false, results of
	 * {@link JedisConnection#closePipeline()} and {@link JedisConnection#exec()} will be of the type returned by the
	 * Jedis driver.
	 *
	 * @param convertPipelineAndTxResults Whether or not to convert pipeline and tx results.
	 */
	public void setConvertPipelineAndTxResults(boolean convertPipelineAndTxResults) {
		this.convertPipelineAndTxResults = convertPipelineAndTxResults;
	}

	/**
	 * @return true when {@link RedisSentinelConfiguration} is present.
	 * @since 1.4
	 */
	public boolean isRedisSentinelAware() {
		return RedisConfiguration.isSentinelConfiguration(configuration);
	}

	/**
	 * @return true when {@link RedisClusterConfiguration} is present.
	 * @since 2.0
	 */
	public boolean isRedisClusterAware() {
		return RedisConfiguration.isClusterConfiguration(configuration);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisConnectionFactory#getSentinelConnection()
	 */
	@Override
	public RedisSentinelConnection getSentinelConnection() {

		assertInitialized();

		if (!isRedisSentinelAware()) {
			throw new InvalidDataAccessResourceUsageException("No Sentinels configured");
		}

		return new JedisSentinelConnection(getActiveSentinel());
	}

	private Jedis getActiveSentinel() {

		Assert.isTrue(RedisConfiguration.isSentinelConfiguration(configuration), "SentinelConfig must not be null!");
		SentinelConfiguration sentinelConfiguration = (SentinelConfiguration) configuration;

		JedisClientConfig clientConfig = createSentinelClientConfig(sentinelConfiguration);
		for (RedisNode node : sentinelConfiguration.getSentinels()) {

			Jedis jedis = null;
			boolean success = false;

			try {

				jedis = new Jedis(new HostAndPort(node.getHost(), node.getPort()), clientConfig);
				if (jedis.ping().equalsIgnoreCase("pong")) {
					success = true;
					return jedis;
				}
			} catch (Exception ex) {
				log.warn(String.format("Ping failed for sentinel host: %s", node.getHost()), ex);
			} finally {
				if (!success && jedis != null) {
					jedis.close();
				}
			}
		}

		throw new InvalidDataAccessResourceUsageException("No Sentinel found");
	}


	private static Set<HostAndPort> convertToJedisSentinelSet(Collection<RedisNode> nodes) {

		if (CollectionUtils.isEmpty(nodes)) {
			return Collections.emptySet();
		}

		Set<HostAndPort> convertedNodes = new LinkedHashSet<>(nodes.size());
		for (RedisNode node : nodes) {
			if (node != null) {
				convertedNodes.add(new HostAndPort(node.getHost(), node.getPort()));
			}
		}
		return convertedNodes;
	}

	private int getReadTimeout() {
		return Math.toIntExact(clientConfiguration.getReadTimeout().toMillis());
	}

	private int getConnectTimeout() {
		return Math.toIntExact(clientConfiguration.getConnectTimeout().toMillis());
	}

	private MutableJedisClientConfiguration getMutableConfiguration() {

		Assert.state(clientConfiguration instanceof MutableJedisClientConfiguration,
				() -> String.format("Client configuration must be instance of MutableJedisClientConfiguration but is %s",
						ClassUtils.getShortName(clientConfiguration.getClass())));

		return (MutableJedisClientConfiguration) clientConfiguration;
	}

	private void assertInitialized() {
		Assert.state(this.initialized, "JedisConnectionFactory was not initialized through afterPropertiesSet()");
		Assert.state(!this.destroyed, "JedisConnectionFactory was destroyed and cannot be used anymore");
	}

	/**
	 * Mutable implementation of {@link JedisClientConfiguration}.
	 *
	 * @author Mark Paluch
	 */
	static class MutableJedisClientConfiguration implements JedisClientConfiguration {

		private boolean useSsl;
		private @Nullable SSLSocketFactory sslSocketFactory;
		private @Nullable SSLParameters sslParameters;
		private @Nullable HostnameVerifier hostnameVerifier;
		private boolean usePooling = true;
		private GenericObjectPoolConfig poolConfig = new JedisPoolConfig();
		private @Nullable String clientName;
		private Duration readTimeout = Duration.ofMillis(Protocol.DEFAULT_TIMEOUT);
		private Duration connectTimeout = Duration.ofMillis(Protocol.DEFAULT_TIMEOUT);

		public static JedisClientConfiguration create(JedisShardInfo shardInfo) {

			MutableJedisClientConfiguration configuration = new MutableJedisClientConfiguration();
			configuration.setShardInfo(shardInfo);
			return configuration;
		}

		public static JedisClientConfiguration create(GenericObjectPoolConfig jedisPoolConfig) {

			MutableJedisClientConfiguration configuration = new MutableJedisClientConfiguration();
			configuration.setPoolConfig(jedisPoolConfig);
			return configuration;
		}

		/* (non-Javadoc)
		 * @see org.springframework.data.redis.connection.jedis.JedisClientConfiguration#isUseSsl()
		 */
		@Override
		public boolean isUseSsl() {
			return useSsl;
		}

		public void setUseSsl(boolean useSsl) {
			this.useSsl = useSsl;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.jedis.JedisClientConfiguration#getSslSocketFactory()
		 */
		@Override
		public Optional<SSLSocketFactory> getSslSocketFactory() {
			return Optional.ofNullable(sslSocketFactory);
		}

		public void setSslSocketFactory(SSLSocketFactory sslSocketFactory) {
			this.sslSocketFactory = sslSocketFactory;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.jedis.JedisClientConfiguration#getSslParameters()
		 */
		@Override
		public Optional<SSLParameters> getSslParameters() {
			return Optional.ofNullable(sslParameters);
		}

		public void setSslParameters(SSLParameters sslParameters) {
			this.sslParameters = sslParameters;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.jedis.JedisClientConfiguration#getHostnameVerifier()
		 */
		@Override
		public Optional<HostnameVerifier> getHostnameVerifier() {
			return Optional.ofNullable(hostnameVerifier);
		}

		public void setHostnameVerifier(HostnameVerifier hostnameVerifier) {
			this.hostnameVerifier = hostnameVerifier;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.jedis.JedisClientConfiguration#isUsePooling()
		 */
		@Override
		public boolean isUsePooling() {
			return usePooling;
		}

		public void setUsePooling(boolean usePooling) {
			this.usePooling = usePooling;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.jedis.JedisClientConfiguration#getPoolConfig()
		 */
		@Override
		public Optional<GenericObjectPoolConfig> getPoolConfig() {
			return Optional.ofNullable(poolConfig);
		}

		public void setPoolConfig(GenericObjectPoolConfig poolConfig) {
			this.poolConfig = poolConfig;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.jedis.JedisClientConfiguration#getClientName()
		 */
		@Override
		public Optional<String> getClientName() {
			return Optional.ofNullable(clientName);
		}

		public void setClientName(String clientName) {
			this.clientName = clientName;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.jedis.JedisClientConfiguration#getReadTimeout()
		 */
		@Override
		public Duration getReadTimeout() {
			return readTimeout;
		}

		public void setReadTimeout(Duration readTimeout) {
			this.readTimeout = readTimeout;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.redis.connection.jedis.JedisClientConfiguration#getConnectTimeout()
		 */
		@Override
		public Duration getConnectTimeout() {
			return connectTimeout;
		}

		public void setConnectTimeout(Duration connectTimeout) {
			this.connectTimeout = connectTimeout;
		}

		public void setShardInfo(JedisShardInfo shardInfo) {

			setSslSocketFactory(shardInfo.getSslSocketFactory());
			setSslParameters(shardInfo.getSslParameters());
			setHostnameVerifier(shardInfo.getHostnameVerifier());
			setUseSsl(shardInfo.getSsl());
			setConnectTimeout(Duration.ofMillis(shardInfo.getConnectionTimeout()));
			setReadTimeout(Duration.ofMillis(shardInfo.getSoTimeout()));
		}
	}
}
