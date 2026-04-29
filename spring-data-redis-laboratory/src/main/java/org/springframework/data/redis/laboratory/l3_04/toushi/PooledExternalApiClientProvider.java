package org.springframework.data.redis.laboratory.l3_04.toushi;

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.time.Duration;

/**
 * 偷师对象 = LettucePoolingConnectionProvider。
 *
 * <p>核心姿势完全照抄 Lettuce：
 * <pre>
 *   borrow      ↔ pool.borrowObject()
 *   release     ↔ pool.returnObject()
 *   destroy/validate ↔ PooledObjectFactory
 *   max-active  ↔ 保护下游 API：再多并发也不会突破 N 条上行
 *   max-wait    ↔ 借不到立刻失败，避免 Tomcat 线程池堆积
 * </pre>
 */
public class PooledExternalApiClientProvider implements ExternalApiClientProvider, AutoCloseable {

	private final GenericObjectPool<ExternalApiClient> pool;

	public PooledExternalApiClientProvider(ExternalApiClientFactory factory, ExternalApiClientPoolConfig cfg) {
		GenericObjectPoolConfig<ExternalApiClient> poolCfg = new GenericObjectPoolConfig<>();
		poolCfg.setMaxTotal(cfg.maxActive);
		poolCfg.setMaxIdle(cfg.maxIdle);
		poolCfg.setMinIdle(cfg.minIdle);
		poolCfg.setMaxWait(Duration.ofMillis(cfg.maxWaitMillis));
		poolCfg.setTestOnBorrow(cfg.testOnBorrow);
		poolCfg.setJmxEnabled(false);
		this.pool = new GenericObjectPool<>(new ApiClientPooledFactory(factory), poolCfg);
	}

	@Override
	public ExternalApiClient borrow() {
		try {
			return pool.borrowObject();
		} catch (Exception e) {
			// 偷师 Spring Data Redis：在边界把第三方异常翻译成业务异常
			throw new ExternalApiPoolException("borrow client failed", e);
		}
	}

	@Override
	public void release(ExternalApiClient client) {
		if (client == null) return;
		pool.returnObject(client);
	}

	public int active() { return pool.getNumActive(); }
	public int idle()   { return pool.getNumIdle(); }

	@Override
	public void close() {
		pool.close();
	}

	private static class ApiClientPooledFactory extends BasePooledObjectFactory<ExternalApiClient> {
		private final ExternalApiClientFactory factory;

		ApiClientPooledFactory(ExternalApiClientFactory factory) {
			this.factory = factory;
		}

		@Override
		public ExternalApiClient create() { return factory.create(); }

		@Override
		public PooledObject<ExternalApiClient> wrap(ExternalApiClient obj) { return new DefaultPooledObject<>(obj); }

		@Override
		public boolean validateObject(PooledObject<ExternalApiClient> p) { return p.getObject().ping(); }

		@Override
		public void destroyObject(PooledObject<ExternalApiClient> p) { p.getObject().close(); }
	}
}
